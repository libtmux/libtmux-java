package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link EventSubscription#publisher()} against the Reactive Streams rules named in its Javadoc:
 * 1.3 (serial signals), 1.9/2.13 (subscribe never throws, a second subscriber is refused through
 * onSubscribe then onError), 3.9 (an invalid request is refused the same way), 3.13 (cancel
 * releases and silences the subscription), and 1.4/1.7 (exactly one terminal signal, matching
 * {@link EventSubscription#next()}'s own cause/completion rule). The Reactive Streams TCK runs
 * against the same publisher in {@link EventSubscriptionTckTest}.
 */
final class EventSubscriptionPublisherTest {

    @Test
    void subscribeRejectsANullSubscriber() {
        try (var subscription = new EventSubscription<String>(4, ignored -> {})) {
            assertThrows(
                    NullPointerException.class, () -> subscription.publisher().subscribe(null));
        }
    }

    @Test
    void aSecondSubscriberIsRefusedAndTheFirstIsUnaffected() throws Exception {
        try (var subscription = new EventSubscription<String>(4, ignored -> {})) {
            Flow.Publisher<Delivery<String>> publisher = subscription.publisher();
            RecordingSubscriber<String> first = new RecordingSubscriber<>();
            publisher.subscribe(first);
            first.awaitOnSubscribe();

            RecordingSubscriber<String> second = new RecordingSubscriber<>();
            publisher.subscribe(second);

            second.awaitOnSubscribe();
            IllegalStateException failure = assertInstanceOf(
                    IllegalStateException.class, second.awaitSignal().error());
            assertTrue(String.valueOf(failure.getMessage()).contains("already"), failure.getMessage());
            assertFalse(
                    subscription.isClosed(), "the first subscriber's subscription must survive a refused second one");

            first.subscription.request(1);
            subscription.offer("first still works");
            assertEquals("first still works", Delivery.kept(first.awaitSignal().requireEvent()));
        }
    }

    @Test
    void requestOfZeroOrNegativeSignalsIllegalArgumentException() throws Exception {
        try (var subscription = new EventSubscription<String>(4, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();

            subscriber.subscription.request(0);

            IllegalArgumentException failure = assertInstanceOf(
                    IllegalArgumentException.class, subscriber.awaitSignal().error());
            assertTrue(String.valueOf(failure.getMessage()).contains("0"), failure.getMessage());
            assertTrue(subscription.isClosed(), "an invalid request must release the subscription");
        }
    }

    @Test
    void aNegativeRequestIsRefusedTheSameWay() throws Exception {
        try (var subscription = new EventSubscription<String>(4, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();

            subscriber.subscription.request(-5);

            assertInstanceOf(
                    IllegalArgumentException.class, subscriber.awaitSignal().error());
        }
    }

    @Test
    void deliveryIsBoundedByRequestedDemand() throws Exception {
        try (var subscription = new EventSubscription<String>(8, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();

            subscriber.subscription.request(2);
            subscription.offer("a");
            subscription.offer("b");
            subscription.offer("c");

            assertEquals("a", Delivery.kept(subscriber.awaitSignal().requireEvent()));
            assertEquals("b", Delivery.kept(subscriber.awaitSignal().requireEvent()));
            assertNull(subscriber.pollSignal(200), "a third element arrived without being requested");

            subscriber.subscription.request(1);
            assertEquals("c", Delivery.kept(subscriber.awaitSignal().requireEvent()));
        }
    }

    @Test
    void longMaxValueRequestIsUnbounded() throws Exception {
        try (var subscription = new EventSubscription<String>(8, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();

            subscriber.subscription.request(Long.MAX_VALUE);
            for (int i = 0; i < 5; i++) {
                subscription.offer("v" + i);
            }
            for (int i = 0; i < 5; i++) {
                assertEquals("v" + i, Delivery.kept(subscriber.awaitSignal().requireEvent()));
            }
        }
    }

    @Test
    void aGapIsDeliveredAsAnOrdinaryElement() throws Exception {
        try (var subscription = new EventSubscription<String>(1, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();
            // Both before any demand: a read starts only once something is requested, so none can
            // take "lost" before "kept" pushes it out of the one-slot buffer.
            subscription.offer("lost");
            subscription.offer("kept");
            subscriber.subscription.request(2);

            var gap = (Delivery.Gap<String>) subscriber.awaitSignal().requireEvent();
            assertEquals(1L, gap.missed());
            assertEquals("kept", Delivery.kept(subscriber.awaitSignal().requireEvent()));
        }
    }

    @Test
    void cancelClosesTheSubscriptionAndIsIdempotentAndSilent() throws Exception {
        try (var subscription = new EventSubscription<String>(4, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();
            subscriber.subscription.request(1);
            subscription.offer("before-cancel");
            assertEquals("before-cancel", Delivery.kept(subscriber.awaitSignal().requireEvent()));

            subscriber.subscription.cancel();
            subscriber.subscription.cancel();

            assertTrue(subscription.isClosed());
            subscriber.subscription.request(5);
            subscription.offer("after-cancel");
            assertNull(subscriber.pollSignal(200), "cancel must silence every later signal, including onComplete");
        }
    }

    /** Rule 2.13: a subscriber that throws from onNext has cancelled, so the subscription is released. */
    @Test
    void aSubscriberThatThrowsIsTreatedAsHavingCancelled() throws Exception {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        try (var subscription = new EventSubscription<String>(4, ignored -> closed.countDown())) {
            subscription.publisher().subscribe(new Flow.Subscriber<Delivery<String>>() {
                @Override
                public void onSubscribe(Flow.Subscription granted) {
                    granted.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Delivery<String> item) {
                    throw new IllegalStateException("a broken subscriber");
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });
            subscription.offer("first");

            assertTrue(
                    closed.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "the subscription stayed open behind a subscriber that threw");
        }
    }

    @Test
    void cancelStopsAWaitingDrainPromptlyWithNoTerminalSignal() throws Exception {
        try (var subscription = new EventSubscription<String>(4, ignored -> {})) {
            RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();
            subscriber.subscription.request(Long.MAX_VALUE);

            subscriber.subscription.cancel();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!subscription.isClosed() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(subscription.isClosed(), "cancel while a drain is blocked in next() must still close promptly");
            assertNull(subscriber.pollSignal(200));
        }
    }

    @Test
    void onCompleteWhenTheSubscriptionClosesNormally() throws Exception {
        var subscription = new EventSubscription<String>(4, ignored -> {});
        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        subscription.publisher().subscribe(subscriber);
        subscriber.awaitOnSubscribe();
        subscriber.subscription.request(Long.MAX_VALUE);
        subscription.offer("one");
        assertEquals("one", Delivery.kept(subscriber.awaitSignal().requireEvent()));

        subscription.close();

        assertTrue(subscriber.awaitSignal().completed());
    }

    @Test
    void onErrorWithTheClientsCauseAfterWhatWasAlreadyBufferedIsDelivered() throws Exception {
        var failure = new IllegalStateException("control client ended");
        var subscription = new EventSubscription<String>(4, ignored -> {});
        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        subscription.publisher().subscribe(subscriber);
        subscriber.awaitOnSubscribe();
        subscriber.subscription.request(Long.MAX_VALUE);
        subscription.offer("one");
        subscription.offer("two");

        subscription.end(failure);

        assertEquals("one", Delivery.kept(subscriber.awaitSignal().requireEvent()));
        assertEquals("two", Delivery.kept(subscriber.awaitSignal().requireEvent()));
        assertSame(failure, subscriber.awaitSignal().error());
    }

    @Test
    void signalsAreSerialUnderConcurrentDemandAndOffers() throws Exception {
        int total = 500;
        try (var subscription = new EventSubscription<Integer>(total, ignored -> {})) {
            RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
            subscription.publisher().subscribe(subscriber);
            subscriber.awaitOnSubscribe();

            ExecutorService requesters = Executors.newFixedThreadPool(4);
            ExecutorService offerers = Executors.newFixedThreadPool(4);
            try {
                for (int i = 0; i < total; i++) {
                    int value = i;
                    var unusedRequest = requesters.submit(() -> subscriber.subscription.request(1));
                    var unusedOffer = offerers.submit(() -> subscription.offer(value));
                }
            } finally {
                requesters.shutdown();
                offerers.shutdown();
                assertTrue(requesters.awaitTermination(10, TimeUnit.SECONDS));
                assertTrue(offerers.awaitTermination(10, TimeUnit.SECONDS));
            }

            for (int i = 0; i < total; i++) {
                subscriber.awaitSignal(10_000).requireEvent();
            }
            assertNull(subscriber.pollSignal(200), "more elements arrived than were requested");
            assertFalse(subscriber.concurrentCall.get(), "two signals to the same subscriber overlapped");
        }
    }

    /** Rule 3.4: request must not throw. An executor that refuses the read ends the subscriber once. */
    @Test
    void aRejectingExecutorEndsTheSubscriberInsteadOfThrowing() throws Exception {
        var subscription = new EventSubscription<String>(4, ignored -> {});
        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        subscription
                .publisher(task -> {
                    throw new RejectedExecutionException("pool is shut down");
                })
                .subscribe(subscriber);
        subscriber.awaitOnSubscribe();

        subscriber.subscription.request(1);

        assertInstanceOf(
                RejectedExecutionException.class, subscriber.awaitSignal().error());
        assertTrue(subscription.isClosed(), "a subscriber that cannot be served releases the subscription");
        subscriber.subscription.request(1);
        assertNull(subscriber.pollSignal(200), "nothing follows the terminal signal");
    }

    /**
     * Records every signal a {@link Flow.Subscriber} receives and detects two arriving at once,
     * which the Reactive Streams rule that signals are serial (1.3) forbids.
     */
    private static final class RecordingSubscriber<T> implements Flow.Subscriber<Delivery<T>> {

        record Signal<T>(
                Flow.@Nullable Subscription subscribed,
                @Nullable Delivery<T> event,
                @Nullable Throwable error,
                boolean completed) {
            static <T> Signal<T> onSubscribe(Flow.Subscription subscription) {
                return new Signal<>(subscription, null, null, false);
            }

            static <T> Signal<T> onNext(Delivery<T> event) {
                return new Signal<>(null, event, null, false);
            }

            static <T> Signal<T> onError(Throwable error) {
                return new Signal<>(null, null, error, false);
            }

            static <T> Signal<T> onComplete() {
                return new Signal<>(null, null, null, true);
            }

            Delivery<T> requireEvent() {
                Delivery<T> value = event;
                if (value == null) {
                    throw new AssertionError("expected onNext, got " + this);
                }
                return value;
            }
        }

        private final BlockingQueue<Signal<T>> signals = new LinkedBlockingQueue<>();
        private final AtomicBoolean busy = new AtomicBoolean();
        final AtomicBoolean concurrentCall = new AtomicBoolean();
        volatile Flow.Subscription subscription = NoopSubscription.INSTANCE;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            enter();
            try {
                this.subscription = subscription;
                signals.add(Signal.onSubscribe(subscription));
            } finally {
                exit();
            }
        }

        @Override
        public void onNext(Delivery<T> item) {
            enter();
            try {
                signals.add(Signal.onNext(item));
            } finally {
                exit();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            enter();
            try {
                signals.add(Signal.onError(throwable));
            } finally {
                exit();
            }
        }

        @Override
        public void onComplete() {
            enter();
            try {
                signals.add(Signal.onComplete());
            } finally {
                exit();
            }
        }

        private void enter() {
            if (!busy.compareAndSet(false, true)) {
                concurrentCall.set(true);
            }
        }

        private void exit() {
            busy.set(false);
        }

        void awaitOnSubscribe() throws InterruptedException {
            Signal<T> signal = awaitSignal();
            if (signal.subscribed() == null) {
                throw new AssertionError("expected onSubscribe, got " + signal);
            }
        }

        Signal<T> awaitSignal() throws InterruptedException {
            return awaitSignal(5_000);
        }

        Signal<T> awaitSignal(long timeoutMillis) throws InterruptedException {
            Signal<T> signal = signals.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (signal == null) {
                throw new AssertionError("no signal arrived within " + timeoutMillis + "ms");
            }
            return signal;
        }

        @Nullable
        Signal<T> pollSignal(long timeoutMillis) throws InterruptedException {
            return signals.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** Every {@link RecordingSubscriber} starts pointed at this until its real onSubscribe arrives. */
    private enum NoopSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
    }
}
