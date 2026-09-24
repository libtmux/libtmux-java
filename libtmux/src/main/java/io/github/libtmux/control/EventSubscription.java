package io.github.libtmux.control;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
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
 * <p>Closing is terminal: it discards buffered events, wakes threads blocked in {@link #next()},
 * and makes every later read return empty once a pending gap has been delivered. Events discarded
 * by close are not overflow and do not increment the loss count.
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

    /** As {@link #close()}, recording why the control client ended this subscription. */
    void end(@Nullable Throwable failure) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            cause = failure;
            closed = true;
            events.clear();
            available.signalAll();
            onClose.accept(this);
        } finally {
            lock.unlock();
        }
    }
}
