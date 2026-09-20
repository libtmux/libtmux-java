package io.github.libtmux.control;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Pulls events volunteered by one control client.
 *
 * <p>Each subscription owns a fixed-capacity buffer. When that buffer is full, the next event
 * replaces its oldest event and increments {@link #droppedCount()}; another subscription has its
 * own buffer and loss count. The control protocol reader only offers values to these buffers. It
 * never runs subscriber code. Byte-limited subscriptions also discard oldest events until the
 * next payload fits. A payload larger than the entire byte limit clears the buffer and is dropped.
 * Use {@link #nextDelivery()} to associate loss with a returned event atomically.
 * Public operations are thread-safe; concurrent readers compete for
 * the same sequence, and each event is returned at most once.
 *
 * <p>Intentional close discards buffered events and wakes waiting readers. Remote termination
 * preserves buffered events for draining before reads return empty. Both are terminal: no further
 * events are accepted. Events discarded by intentional close do not increment overflow counts.
 *
 * @param <T> the event type
 */
public final class EventSubscription<T> implements AutoCloseable {

    /** Describes why a subscription ended; UNKNOWN does not imply a server failure. */
    public enum EndReason {
        CLOSED,
        CONTROL_EXIT,
        TRANSPORT_FAILURE,
        PROTOCOL_FAILURE,
        UNKNOWN
    }

    /** Preserves the first observed terminal reason and its original exception, if known. */
    public record Termination(EndReason reason, Optional<Throwable> cause) {}

    /** Associates an event with the loss counters at removal from the buffer. */
    public record Delivery<T>(T event, long droppedCount, long droppedBytes) {}

    private final int capacity;
    private final long maxBytes;
    private final ToLongFunction<T> size;
    private long retainedBytes;
    private long droppedBytes;
    private @Nullable Termination termination;
    private final ArrayDeque<T> events = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Consumer<EventSubscription<T>> onClose;
    private long dropped;
    private boolean closed;

    EventSubscription(int capacity, Consumer<EventSubscription<T>> onClose) {
        this(capacity, Long.MAX_VALUE, ignored -> 0, onClose);
    }

    EventSubscription(int capacity, long maxBytes, ToLongFunction<T> size, Consumer<EventSubscription<T>> onClose) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.size = size;
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
            long bytes = size.applyAsLong(event);
            if (bytes < 0) {
                throw new IllegalArgumentException("event size is negative");
            }
            while (!events.isEmpty() && (events.size() == capacity || bytes > maxBytes - retainedBytes)) {
                long removed = size.applyAsLong(events.removeFirst());
                retainedBytes -= removed;
                droppedBytes += removed;
                dropped++;
            }
            if (bytes > maxBytes) {
                dropped++;
                droppedBytes += bytes;
                return;
            }
            events.addLast(event);
            retainedBytes += bytes;
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
        return nextDelivery().map(Delivery::event);
    }

    /** Waits for an event and returns its loss counters under the same lock. */
    public Optional<Delivery<T>> nextDelivery() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (events.isEmpty() && !closed) {
                available.await();
            }
            return poll();
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
        return nextDelivery(timeout).map(Delivery::event);
    }

    /** Waits up to a deadline and returns the event with its atomic loss snapshot. */
    public Optional<Delivery<T>> nextDelivery(Duration timeout) throws InterruptedException {
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
            return poll();
        } finally {
            lock.unlock();
        }
    }

    private Optional<Delivery<T>> poll() {
        T event = events.pollFirst();
        if (event == null) {
            return Optional.empty();
        }
        retainedBytes -= size.applyAsLong(event);
        return Optional.of(new Delivery<>(event, dropped, droppedBytes));
    }

    /** Returns the retained payload byte count, excluding object overhead. */
    public long retainedBytes() {
        lock.lock();
        try {
            return retainedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the monotonic count of payload bytes discarded by overflow. */
    public long droppedBytes() {
        lock.lock();
        try {
            return droppedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the first terminal cause, or empty while open. */
    public Optional<Termination> termination() {
        lock.lock();
        try {
            return Optional.ofNullable(termination);
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
        end(new Termination(EndReason.CLOSED, Optional.empty()));
    }

    void end(Termination reason) {
        lock.lock();
        try {
            if (reason.reason() == EndReason.CLOSED) {
                events.clear();
                retainedBytes = 0;
            }
            if (closed) {
                return;
            }
            closed = true;
            termination = reason;
            available.signalAll();
            onClose.accept(this);
        } finally {
            lock.unlock();
        }
    }
}
