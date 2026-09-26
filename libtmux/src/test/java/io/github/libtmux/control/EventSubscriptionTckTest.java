package io.github.libtmux.control;

import java.util.concurrent.Flow;
import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowPublisherVerification;

/** The Reactive Streams TCK, run against {@link EventSubscription#publisher()}. */
public class EventSubscriptionTckTest extends FlowPublisherVerification<Delivery<Long>> {

    public EventSubscriptionTckTest() {
        super(new TestEnvironment(1_000));
    }

    /** {@code elements} steps already delivered by a producer that then ends cleanly. */
    @Override
    public Flow.Publisher<Delivery<Long>> createFlowPublisher(long elements) {
        EventSubscription<Long> subscription = new EventSubscription<>((int) Math.max(1, elements), ignored -> {});
        for (long value = 0; value < elements; value++) {
            subscription.offer(value);
        }
        subscription.finish();
        return subscription.publisher();
    }

    @Override
    public Flow.Publisher<Delivery<Long>> createFailedFlowPublisher() {
        EventSubscription<Long> subscription = new EventSubscription<>(1, ignored -> {});
        subscription.end(new IllegalStateException("the control client ended"));
        return subscription.publisher();
    }

    /** A buffer holds every element up front, so a finite stream is bounded by its capacity. */
    @Override
    public long maxElementsFromPublisher() {
        return 1_024;
    }
}
