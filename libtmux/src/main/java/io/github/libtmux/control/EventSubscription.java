package io.github.libtmux.control;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Pulls events volunteered by one control client.
 *
 * <p>Each subscription owns a fixed-capacity buffer. When that buffer is full, the next event
 * replaces its oldest event and increments {@link #droppedCount()}; another subscription has its
 * own buffer and loss count. The control protocol reader only offers values to these buffers. It
 * never runs subscriber code. Public operations are thread-safe; concurrent readers compete for
 * the same sequence, and each event is returned at most once.
 *
 * <p>Closing is terminal: it discards buffered events, wakes threads blocked in {@link #next()},
 * and makes every later read return empty. Events discarded by close are not overflow and do not
 * increment the loss count.
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
    private boolean closed;

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
     * Waits until the next event arrives or this subscription closes.
     *
     * @return the oldest buffered event, or empty when the subscription closed
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public Optional<T> next() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (events.isEmpty() && !closed) {
                available.await();
            }
            return Optional.ofNullable(events.pollFirst());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to a deadline for the next event.
     *
     * @param timeout how long to wait, zero to inspect the buffer without waiting
     * @return the oldest buffered event, or empty when none arrived before the deadline
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public Optional<T> next(Duration timeout) throws InterruptedException {
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
            while (events.isEmpty() && !closed && remaining > 0) {
                remaining = available.awaitNanos(remaining);
            }
            return Optional.ofNullable(events.pollFirst());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether this subscription has reached its terminal state.
     *
     * <p>This distinguishes a timed read that expired from one that returned empty because the
     * subscription ended.
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
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            events.clear();
            available.signalAll();
            onClose.accept(this);
        } finally {
            lock.unlock();
        }
    }
}
