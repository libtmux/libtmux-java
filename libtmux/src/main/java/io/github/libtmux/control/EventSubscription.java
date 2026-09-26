package io.github.libtmux.control;

import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import io.github.libtmux.exception.ControlEndedException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Pulls events volunteered by one control client.
 *
 * <p>Each subscription owns a fixed-capacity buffer. When that buffer is full, the next event
 * replaces its oldest event. The read that follows is a {@link Delivery.Gap} naming how many were
 * discarded since the previous read, and only then the events that remain. {@link #droppedCount()}
 * is the total across the subscription's life. Another subscription has its own buffer and its own
 * gaps. The control protocol reader only offers values to these buffers, and never runs subscriber
 * code beyond a {@link #onReady readiness callback}.
 *
 * <p>One reader at a time. Public operations are thread-safe, and reads from different threads one
 * after another are fine — a coroutine or a fiber moves between threads — but a read that overlaps
 * another, or any read once {@link #stream()} or {@link #publisher()} has taken the subscription, is
 * refused with {@link IllegalStateException}: two readers would split one sequence and its gaps
 * between them. For a second reader, subscribe again; each subscription gets every event.
 *
 * <p>{@link #next()} blocks. {@link #poll()} never does, and {@link #onReady(Runnable)} arms a
 * one-shot wakeup for when there is something to poll, so an event loop or a coroutine can read
 * without holding a thread while nothing arrives.
 *
 * <p>A subscription does not reconnect. When it ends, {@link #next()} returns empty. {@link
 * #cause()} is empty when the caller closed it, and set when the control client ended it. Attach
 * again from the captured session and read a snapshot; nothing already missed is replayed.
 *
 * <p>Ending is terminal and wakes threads blocked in {@link #next()}. A caller's {@link #close()}
 * discards buffered events: the caller has chosen not to read them, so they are not overflow and do
 * not increment the loss count. When the control client ends a subscription, what it had already
 * delivered stays readable, a pending gap first, and only then does a read return empty with
 * {@link #cause()} set.
 *
 * @param <T> the event type
 */
public final class EventSubscription<T> implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EventSubscription.class.getName());

    private final int capacity;
    private final ArrayDeque<T> events = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Consumer<EventSubscription<T>> onClose;
    private long dropped;
    private long pendingGap;
    private boolean closed;
    private @Nullable Throwable cause;
    // A read is in progress, on a thread that may be waiting with the lock released.
    private boolean reading;
    // stream() or publisher() owns every read from now on.
    private boolean claimed;
    private @Nullable Runnable ready;
    // The owning publisher's wakeup when this subscription ends: a terminal signal needs no demand.
    private @Nullable Runnable ownerWake;

    EventSubscription(int capacity, Consumer<EventSubscription<T>> onClose) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.onClose = onClose;
    }

    void offer(T event) {
        @Nullable Runnable wake = null;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            boolean wasReadable = readable();
            if (events.size() == capacity) {
                events.removeFirst();
                dropped++;
                pendingGap++;
            }
            events.addLast(event);
            available.signal();
            if (!wasReadable) {
                wake = ready;
                ready = null;
            }
        } finally {
            lock.unlock();
        }
        if (wake != null) {
            fire(wake);
        }
    }

    private boolean readable() {
        return pendingGap > 0 || !events.isEmpty();
    }

    /**
     * Returns the exact number of events discarded because this subscription's buffer was full.
     *
     * @return the monotonic loss count
     */
    @Operation(Kind.CAPTURED)
    public long droppedCount() {
        lock.lock();
        try {
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Why this subscription ended, when the control client ended it.
     *
     * <p>Empty when the subscription is still open, and empty when the caller closed it. A client
     * that died sets this before the read returns empty.
     */
    @Operation(Kind.CAPTURED)
    public Optional<Throwable> cause() {
        lock.lock();
        try {
            return Optional.ofNullable(cause);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until the next step arrives or this subscription closes.
     *
     * @return the next gap or event, or empty when the subscription has closed and no gap is waiting
     * @throws IllegalStateException if another read is in progress, or a stream or publisher owns
     *     this subscription
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @Operation(Kind.WAIT)
    public Optional<Delivery<T>> next() throws InterruptedException {
        return read(false, -1);
    }

    /**
     * Waits up to a deadline for the next step.
     *
     * @param timeout how long to wait, zero to inspect the buffer without waiting
     * @return the next gap or event, or empty when none arrived before the deadline
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws IllegalStateException if another read is in progress, or a stream or publisher owns
     *     this subscription
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @Operation(Kind.WAIT)
    public Optional<Delivery<T>> next(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remaining;
        try {
            remaining = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            remaining = Long.MAX_VALUE;
        }
        return read(false, remaining);
    }

    /**
     * Returns the next step if one is waiting, without blocking.
     *
     * @return the next gap or event, or empty when none is buffered; after the subscription has
     *     ended, empty once what it held is read
     * @throws IllegalStateException if another read is in progress, or a stream or publisher owns
     *     this subscription
     */
    @Operation(Kind.CAPTURED)
    public Optional<Delivery<T>> poll() {
        lock.lock();
        try {
            enter(false);
            try {
                return take();
            } finally {
                reading = false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Arms a one-shot wakeup for when this subscription has something to read, or has ended.
     *
     * <p>Runs {@code callback} at once, on this thread, when a step is already waiting or the
     * subscription has ended. Otherwise it runs once, on the control client's reader thread, when the
     * first step arrives or the subscription ends, and is then disarmed: drain with {@link #poll()}
     * until it is empty, then arm again. Checking and arming happen under the lock that delivery
     * takes, so a step cannot slip between them unannounced.
     *
     * <p>The callback runs on the thread delivering events, so it must not block: resume a waiting
     * coroutine, complete a promise, and return. One that throws is logged and ignored, and delivery
     * continues.
     *
     * @throws IllegalStateException if a callback is already armed, or a stream or publisher owns
     *     this subscription
     */
    @Operation(Kind.CAPTURED)
    public void onReady(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        boolean now;
        lock.lock();
        try {
            if (claimed) {
                throw owned();
            }
            if (ready != null) {
                throw new IllegalStateException("a readiness callback is already armed; clearReady() first");
            }
            now = readable() || closed;
            if (!now) {
                ready = callback;
            }
        } finally {
            lock.unlock();
        }
        if (now) {
            fire(callback);
        }
    }

    /** Disarms the readiness callback, if one is armed. For a reader that stopped waiting. */
    @Operation(Kind.CAPTURED)
    public void clearReady() {
        lock.lock();
        try {
            ready = null;
        } finally {
            lock.unlock();
        }
    }

    private static void fire(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException broken) {
            LOG.log(System.Logger.Level.WARNING, "a subscription's readiness callback failed", broken);
        }
    }

    /** One read, waiting up to {@code timeoutNanos}, or without limit when it is negative. */
    private Optional<Delivery<T>> read(boolean owner, long timeoutNanos) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            enter(owner);
            try {
                long remaining = timeoutNanos;
                while (!readable() && !closed && (timeoutNanos < 0 || remaining > 0)) {
                    if (timeoutNanos < 0) {
                        available.await();
                    } else {
                        remaining = available.awaitNanos(remaining);
                    }
                }
                return take();
            } finally {
                reading = false;
            }
        } finally {
            lock.unlock();
        }
    }

    private void enter(boolean owner) {
        if (claimed && !owner) {
            throw owned();
        }
        if (reading) {
            throw new IllegalStateException(
                    "another thread is reading this subscription; read from one thread at a time, or"
                            + " subscribe again for a second reader");
        }
        reading = true;
    }

    /** Hands every future read to a stream or publisher, which becomes the only reader. */
    private void claim(@Nullable Runnable onEnd) {
        lock.lock();
        try {
            if (claimed || reading || ready != null) {
                throw new IllegalStateException(
                        "this subscription already has a reader; subscribe again for a second one");
            }
            claimed = true;
            ownerWake = onEnd;
        } finally {
            lock.unlock();
        }
    }

    /** Forgets the owning publisher's wakeup, so a cancelled subscriber is not kept reachable. */
    private void releaseOwner() {
        lock.lock();
        try {
            ownerWake = null;
        } finally {
            lock.unlock();
        }
    }

    /** Whether this subscription has ended and everything it held has been read. */
    private boolean exhausted() {
        lock.lock();
        try {
            return closed && !readable();
        } finally {
            lock.unlock();
        }
    }

    private static IllegalStateException owned() {
        return new IllegalStateException(
                "a stream or publisher owns this subscription's reads; subscribe again for another reader");
    }

    private Optional<Delivery<T>> take() {
        if (pendingGap > 0) {
            long missed = pendingGap;
            pendingGap = 0;
            return Optional.of(new Delivery.Gap<>(missed));
        }
        T value = events.pollFirst();
        return value == null ? Optional.empty() : Optional.of(new Delivery.Event<>(value));
    }

    /**
     * The steps still to come, in order, each read by the thread consuming the stream.
     *
     * <p>Pull-only: a step is read when the stream asks for one, and events that arrive meanwhile
     * wait in this subscription's buffer, so its capacity alone decides what becomes a {@link
     * Delivery.Gap}. The stream ends when this subscription closes. When the control client ended
     * it, the stream fails with {@link #cause()}. Closing the stream closes this subscription, and
     * closing this subscription from another thread ends a stream that is waiting.
     *
     * <pre>{@code
     * try (Stream<Delivery<PaneOutput>> steps = subscription.stream()) {
     *     steps.map(Delivery::kept).forEach(output -> System.out.print(output.data()));
     * }
     * }</pre>
     *
     * @throws io.github.libtmux.exception.LibTmuxException from the stream if the reading thread is
     *     interrupted; its interrupt status is set again
     */
    @Operation(Kind.STREAM)
    public java.util.stream.Stream<Delivery<T>> stream() {
        claim(null);
        java.util.Spliterator<Delivery<T>> steps =
                new java.util.Spliterators.AbstractSpliterator<>(
                        Long.MAX_VALUE, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
                    @Override
                    public boolean tryAdvance(java.util.function.Consumer<? super Delivery<T>> action) {
                        Optional<Delivery<T>> step;
                        try {
                            step = read(true, -1);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw cancelled(interrupted);
                        }
                        if (step.isPresent()) {
                            action.accept(step.get());
                            return true;
                        }
                        Optional<Throwable> failure = cause();
                        if (failure.isPresent()) {
                            throw failure.get() instanceof RuntimeException unchecked
                                    ? unchecked
                                    : new ControlEndedException("", false, failure.get());
                        }
                        return false;
                    }
                };
        return java.util.stream.StreamSupport.stream(steps, false).onClose(this::close);
    }

    /**
     * A {@link Flow.Publisher} view of this subscription's steps, delivered as demand allows.
     *
     * <p>Reads that wait for the next step run on a fresh virtual thread, started only while a
     * subscriber has outstanding demand, so a subscriber that never asks never blocks anything. Use
     * {@link #publisher(Executor)} to read somewhere else instead.
     *
     * @see #publisher(Executor)
     */
    @Operation(Kind.STREAM)
    public Flow.Publisher<Delivery<T>> publisher() {
        return publisher(VIRTUAL_THREAD_PER_READ);
    }

    /**
     * A {@link Flow.Publisher} view of this subscription's steps, delivered as demand allows, its
     * reads run on {@code executor} rather than a virtual thread.
     *
     * <p>One subscriber per publisher, the way a {@link #stream()} is read by one consumer: {@link
     * Flow.Publisher#subscribe subscribe} never throws for a non-null subscriber, but a second one on
     * the same publisher is sent {@link Flow.Subscriber#onSubscribe onSubscribe} immediately followed
     * by {@link IllegalStateException} to {@link Flow.Subscriber#onError onError}, rather than sharing
     * this subscription's steps or silently missing them. The first subscriber takes this subscription
     * whole, so a subscriber to a second publisher from it, or one arriving after {@link #stream()},
     * is refused the same way. {@link Flow.Subscription#request request} of zero or less is answered the same way,
     * with {@link IllegalArgumentException}. {@link Long#MAX_VALUE} demand is unbounded. Every signal
     * to one subscriber comes from one thread at a time, never concurrently.
     *
     * <p>{@link Flow.Subscription#cancel cancel} closes this subscription, the same as {@link
     * #close()}, is idempotent, and no signal follows it, not even {@code onComplete}. Otherwise this
     * ends the same way {@link #next()} does: {@code onComplete} when the subscription closes
     * normally, {@code onError} with {@link #cause()} when the control client ended it. A {@link
     * Delivery.Gap} is delivered like any other step.
     *
     * <p>Demand only bounds what reaches the subscriber. tmux cannot be paused through this path —
     * that is {@code pause-after}, in the streaming guide — so this subscription's own bounded buffer
     * keeps filling regardless of demand, and a full buffer still reports its loss as a {@link
     * Delivery.Gap}, exactly as {@link #next()} does.
     *
     * @param executor where each blocking read for the subscriber runs
     */
    @Operation(Kind.STREAM)
    public Flow.Publisher<Delivery<T>> publisher(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return new EventPublisher(executor);
    }

    private static final Executor VIRTUAL_THREAD_PER_READ =
            task -> Thread.ofVirtual().name("libtmux-control-publisher").start(task);

    private static final Flow.Subscription NOOP_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
    };

    private final class EventPublisher implements Flow.Publisher<Delivery<T>> {

        private final Executor executor;
        private final AtomicBoolean claimed = new AtomicBoolean();

        EventPublisher(Executor executor) {
            this.executor = executor;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super Delivery<T>> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!claimed.compareAndSet(false, true)) {
                subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                subscriber.onError(new IllegalStateException(
                        "this publisher already has a subscriber; subscribe to the control client again for another"));
                return;
            }
            DemandSubscription demand = new DemandSubscription(subscriber, executor);
            try {
                claim(demand::schedule);
            } catch (IllegalStateException taken) {
                subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                subscriber.onError(taken);
                return;
            }
            // Rule 1.3: no signal may overlap onSubscribe, so a request made inside it waits for it.
            demand.wip.set(1);
            subscriber.onSubscribe(demand);
            demand.subscribed();
        }
    }

    /**
     * Turns requested demand into reads. A single-flight drain loop, one virtual thread or executor
     * task at a time, is what keeps every signal to {@code subscriber} serial: {@link #request} and
     * {@link #cancel} only ever flip flags and reschedule it, and {@link #drain} is the one place that
     * calls back into {@code subscriber}.
     */
    private final class DemandSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super Delivery<T>> subscriber;
        private final Executor executor;
        private final AtomicLong requested = new AtomicLong();
        private final AtomicInteger wip = new AtomicInteger();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile @Nullable Throwable protocolError;

        DemandSubscription(Flow.Subscriber<? super Delivery<T>> subscriber, Executor executor) {
            this.subscriber = subscriber;
            this.executor = executor;
        }

        @Override
        public void request(long n) {
            if (cancelled) {
                return;
            }
            if (n <= 0) {
                protocolError = new IllegalArgumentException("request must be positive: " + n);
                cancelled = true;
                EventSubscription.this.close();
                schedule();
                return;
            }
            addCap(n);
            schedule();
        }

        @Override
        public void cancel() {
            cancelled = true;
            EventSubscription.this.close();
            EventSubscription.this.releaseOwner();
        }

        private void addCap(long n) {
            for (; ; ) {
                long current = requested.get();
                if (current == Long.MAX_VALUE) {
                    return;
                }
                long next = current + n;
                if (next < 0) {
                    next = Long.MAX_VALUE;
                }
                if (requested.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        /** Runs what arrived during onSubscribe, or looks once for an end that needs no demand. */
        private void subscribed() {
            if (wip.decrementAndGet() > 0) {
                start();
            } else {
                schedule();
            }
        }

        private void schedule() {
            if (wip.getAndIncrement() == 0) {
                start();
            }
        }

        private void start() {
            try {
                executor.execute(this::drain);
            } catch (RuntimeException rejected) {
                // Rule 3.4: request must not throw. No drain runs, so nothing else can signal.
                cancelled = true;
                EventSubscription.this.close();
                Throwable earlier = protocolError;
                signalError(earlier != null ? earlier : rejected);
            }
        }

        private void drain() {
            int missed = 1;
            for (; ; ) {
                if (cancelled) {
                    signalProtocolError();
                    return;
                }
                long r = requested.get();
                long emitted = 0;
                while (r == Long.MAX_VALUE || emitted < r) {
                    if (cancelled) {
                        signalProtocolError();
                        return;
                    }
                    Optional<Delivery<T>> step;
                    try {
                        step = EventSubscription.this.read(true, -1);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                        EventSubscription.this.close();
                        signalError(cancelled(interrupted));
                        return;
                    }
                    if (cancelled) {
                        signalProtocolError();
                        return;
                    }
                    if (step.isPresent()) {
                        try {
                            subscriber.onNext(step.get());
                        } catch (RuntimeException broken) {
                            // Rule 2.13: a subscriber that throws has cancelled. Release what it held,
                            // and signal nothing more to it.
                            cancelled = true;
                            EventSubscription.this.close();
                            return;
                        } catch (Error fatal) {
                            cancelled = true;
                            EventSubscription.this.close();
                            throw fatal;
                        }
                        emitted++;
                        continue;
                    }
                    cancelled = true;
                    signalEnd();
                    return;
                }
                if (emitted != 0 && r != Long.MAX_VALUE) {
                    requested.addAndGet(-emitted);
                }
                // Rules 1.4 and 1.5: an end is signalled without waiting for demand.
                if (EventSubscription.this.exhausted()) {
                    cancelled = true;
                    signalEnd();
                    return;
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
            }
        }

        private void signalProtocolError() {
            Throwable error = protocolError;
            if (error != null) {
                signalError(error);
            }
        }

        private void signalEnd() {
            Optional<Throwable> failure = EventSubscription.this.cause();
            if (failure.isPresent()) {
                signalError(failure.get());
            } else if (terminated.compareAndSet(false, true)) {
                EventSubscription.this.releaseOwner();
                subscriber.onComplete();
            }
        }

        /** The one terminal signal, however many paths race to send it. */
        private void signalError(Throwable error) {
            if (terminated.compareAndSet(false, true)) {
                EventSubscription.this.releaseOwner();
                subscriber.onError(error);
            }
        }
    }

    /**
     * Whether this subscription has reached its terminal state.
     *
     * <p>This distinguishes a timed read that expired from one that returned empty because the
     * subscription ended. A pending gap can still be read after the subscription has closed.
     */
    @Operation(Kind.CAPTURED)
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** Discards buffered events, removes this subscriber, and wakes every waiting reader. */
    @Operation(Kind.LIFECYCLE)
    @Override
    public void close() {
        end(null);
    }

    /**
     * Ends this subscription as the control client saw it end, recording why. Unlike {@link
     * #close()}, events already delivered stay readable.
     */
    void end(@Nullable Throwable failure) {
        end(failure, failure == null);
    }

    /** Ends this subscription as a producer with nothing more to send: not a failure, and nothing is discarded. */
    void finish() {
        end(null, false);
    }

    private void end(@Nullable Throwable failure, boolean discard) {
        @Nullable Runnable wake;
        @Nullable Runnable owner;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            cause = failure;
            closed = true;
            if (discard) {
                events.clear();
            }
            available.signalAll();
            wake = ready;
            ready = null;
            owner = ownerWake;
            ownerWake = null;
            onClose.accept(this);
        } finally {
            lock.unlock();
        }
        if (wake != null) {
            fire(wake);
        }
        if (owner != null) {
            fire(owner);
        }
    }

    /** An interrupt on a read that cannot throw {@link InterruptedException}: the wait was cancelled. */
    private static CancellationException cancelled(InterruptedException interrupted) {
        CancellationException cancelled = new CancellationException("interrupted waiting for the next event");
        cancelled.initCause(interrupted);
        return cancelled;
    }
}
