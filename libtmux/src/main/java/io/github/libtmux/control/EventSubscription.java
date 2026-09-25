package io.github.libtmux.control;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
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
 * gaps. The control protocol reader only offers values to these buffers. It never runs subscriber
 * code. Public operations are thread-safe; concurrent readers compete for the same sequence, and
 * each step is returned at most once.
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

    private final int capacity;
    private final ArrayDeque<T> events = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Consumer<EventSubscription<T>> onClose;
    private long dropped;
    private long pendingGap;
    private boolean closed;
    private @Nullable Throwable cause;

    EventSubscription(int capacity, Consumer<EventSubscription<T>> onClose) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.onClose = onClose;
    }

    void offer(T event) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            if (events.size() == capacity) {
                events.removeFirst();
                dropped++;
                pendingGap++;
            }
            events.addLast(event);
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the exact number of events discarded because this subscription's buffer was full.
     *
     * @return the monotonic loss count
     */
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
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public Optional<Delivery<T>> next() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (pendingGap == 0 && events.isEmpty() && !closed) {
                available.await();
            }
            return poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to a deadline for the next step.
     *
     * @param timeout how long to wait, zero to inspect the buffer without waiting
     * @return the next gap or event, or empty when none arrived before the deadline
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws InterruptedException if the waiting thread is interrupted
     */
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
        lock.lockInterruptibly();
        try {
            while (pendingGap == 0 && events.isEmpty() && !closed && remaining > 0) {
                remaining = available.awaitNanos(remaining);
            }
            return poll();
        } finally {
            lock.unlock();
        }
    }

    private Optional<Delivery<T>> poll() {
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
     * @throws io.github.libtmux.LibTmuxException from the stream if the reading thread is
     *     interrupted; its interrupt status is set again
     */
    public java.util.stream.Stream<Delivery<T>> stream() {
        java.util.Spliterator<Delivery<T>> steps =
                new java.util.Spliterators.AbstractSpliterator<>(
                        Long.MAX_VALUE, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
                    @Override
                    public boolean tryAdvance(java.util.function.Consumer<? super Delivery<T>> action) {
                        Optional<Delivery<T>> step;
                        try {
                            step = next();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new io.github.libtmux.LibTmuxException(
                                    "interrupted waiting for the next event", interrupted);
                        }
                        if (step.isPresent()) {
                            action.accept(step.get());
                            return true;
                        }
                        Optional<Throwable> failure = cause();
                        if (failure.isPresent()) {
                            throw failure.get() instanceof RuntimeException unchecked
                                    ? unchecked
                                    : new io.github.libtmux.LibTmuxException("the control client ended", failure.get());
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
     * this subscription's steps or silently missing them. Reading through two publishers taken from
     * this subscription splits its steps between them, the same as two concurrent {@link #stream()}
     * calls would. {@link Flow.Subscription#request request} of zero or less is answered the same way,
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
                        "this subscription already has a subscriber; call publisher() again for a new one"));
                return;
            }
            subscriber.onSubscribe(new DemandSubscription(subscriber, executor));
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

        private void schedule() {
            if (wip.getAndIncrement() == 0) {
                executor.execute(this::drain);
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
                        step = EventSubscription.this.next();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                        EventSubscription.this.close();
                        subscriber.onError(new io.github.libtmux.LibTmuxException(
                                "interrupted waiting for the next event", interrupted));
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
                    Optional<Throwable> failure = EventSubscription.this.cause();
                    if (failure.isPresent()) {
                        subscriber.onError(failure.get());
                    } else {
                        subscriber.onComplete();
                    }
                    return;
                }
                if (emitted != 0 && r != Long.MAX_VALUE) {
                    requested.addAndGet(-emitted);
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
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** Discards buffered events, removes this subscriber, and wakes every waiting reader. */
    @Override
    public void close() {
        end(null);
    }

    /**
     * Ends this subscription as the control client saw it end, recording why. Unlike {@link
     * #close()}, events already delivered stay readable.
     */
    void end(@Nullable Throwable failure) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            cause = failure;
            closed = true;
            if (failure == null) {
                events.clear();
            }
            available.signalAll();
            onClose.accept(this);
        } finally {
            lock.unlock();
        }
    }
}
