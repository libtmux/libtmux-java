package io.github.libtmux.control;

/**
 * One step in a control subscription.
 *
 * <p>A {@link Gap} sits in the sequence where events were discarded, ahead of the events that
 * survived. It is not a side total: the next read is the gap, and the one after it is whatever
 * remains.
 *
 * @param <T> the event type
 */
public sealed interface Delivery<T> {

    /**
     * An event the subscription kept.
     *
     * @param value the event
     * @param <T> the event type
     */
    record Event<T>(T value) implements Delivery<T> {}

    /**
     * How many events were discarded since the previous read because the buffer was full.
     *
     * @param missed the number discarded, at least one
     * @param <T> the event type
     */
    record Gap<T>(long missed) implements Delivery<T> {

        public Gap {
            if (missed < 1) {
                throw new IllegalArgumentException("missed is not positive: " + missed);
            }
        }
    }
}
