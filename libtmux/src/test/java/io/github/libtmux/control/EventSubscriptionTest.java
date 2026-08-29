package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class EventSubscriptionTest {

    @Test
    void capacityMustLeaveRoomForOneValue() {
        assertThrows(IllegalArgumentException.class, () -> new EventSubscription<String>(0, ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> new EventSubscription<String>(-1, ignored -> {}));
    }

    @Test
    void aNegativeWaitIsRejected() {
        try (var subscription = new EventSubscription<String>(1, ignored -> {})) {
            assertThrows(IllegalArgumentException.class, () -> subscription.next(Duration.ofNanos(-1)));
        }
    }

    @Test
    void aLargeValidWaitDoesNotOverflow() throws Exception {
        try (var subscription = new EventSubscription<String>(1, ignored -> {})) {
            subscription.offer("ready");

            assertEquals(Optional.of("ready"), subscription.next(Duration.ofSeconds(Long.MAX_VALUE)));
        }
    }

    @Test
    void nextReturnsBufferedValuesInArrivalOrder() throws Exception {
        try (var subscription = new EventSubscription<String>(2, ignored -> {})) {
            subscription.offer("first");
            subscription.offer("second");

            assertEquals(Optional.of("first"), subscription.next(Duration.ZERO));
            assertEquals(Optional.of("second"), subscription.next(Duration.ZERO));
        }
    }

    @Test
    void aFullBufferDropsTheOldestValueAndCountsTheLoss() throws Exception {
        try (var subscription = new EventSubscription<String>(2, ignored -> {})) {
            subscription.offer("first");
            subscription.offer("second");
            subscription.offer("third");

            assertEquals(1, subscription.droppedCount());
            assertEquals(Optional.of("second"), subscription.next(Duration.ZERO));
            assertEquals(Optional.of("third"), subscription.next(Duration.ZERO));
        }
    }

    @Test
    void nextWaitsUntilAValueArrives() throws Exception {
        try (var subscription = new EventSubscription<String>(1, ignored -> {})) {
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<Optional<String>> waiting = new FutureTask<>(() -> {
                entered.countDown();
                return subscription.next();
            });
            Thread consumer = Thread.ofVirtual().start(waiting);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
            assertFalse(waiting.isDone());

            subscription.offer("arrived");
            assertEquals(Optional.of("arrived"), waiting.get(5, TimeUnit.SECONDS));
            consumer.join();
        }
    }

    @Test
    void closeIsTerminalAndDiscardsBufferedValues() throws Exception {
        var subscription = new EventSubscription<String>(2, ignored -> {});
        subscription.offer("buffered");

        subscription.close();
        subscription.offer("after-close");

        assertTrue(subscription.isClosed());
        assertEquals(Optional.empty(), subscription.next(Duration.ZERO));
        assertEquals(Optional.empty(), subscription.next());
    }

    @Test
    void closeWakesAWaitingConsumer() throws Exception {
        var subscription = new EventSubscription<String>(1, ignored -> {});
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<Optional<String>> waiting = new FutureTask<>(() -> {
            entered.countDown();
            return subscription.next();
        });
        Thread consumer = Thread.ofVirtual().start(waiting);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));

            subscription.close();

            assertEquals(Optional.empty(), waiting.get(1, TimeUnit.SECONDS));
        } finally {
            waiting.cancel(true);
            consumer.join();
            subscription.close();
        }
    }

    @Test
    void closeRemovesTheSubscriberExactlyOnce() {
        AtomicInteger removals = new AtomicInteger();
        var subscription = new EventSubscription<String>(1, ignored -> removals.incrementAndGet());

        subscription.close();
        subscription.close();

        assertEquals(1, removals.get());
    }

    @Test
    void everyCloseWaitsForDeterministicRemoval() throws Exception {
        CountDownLatch removalStarted = new CountDownLatch(1);
        CountDownLatch allowRemoval = new CountDownLatch(1);
        var subscription = new EventSubscription<String>(1, ignored -> {
            removalStarted.countDown();
            try {
                allowRemoval.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        FutureTask<Void> firstClose = closeTask(subscription);
        FutureTask<Void> secondClose = closeTask(subscription);
        Thread first = Thread.ofVirtual().start(firstClose);
        Thread second = null;
        try {
            assertTrue(removalStarted.await(5, TimeUnit.SECONDS));
            second = Thread.ofVirtual().start(secondClose);

            assertThrows(TimeoutException.class, () -> secondClose.get(100, TimeUnit.MILLISECONDS));

            allowRemoval.countDown();
            assertEquals(null, firstClose.get(1, TimeUnit.SECONDS));
            assertEquals(null, secondClose.get(1, TimeUnit.SECONDS));
        } finally {
            allowRemoval.countDown();
            firstClose.cancel(true);
            secondClose.cancel(true);
            first.join();
            if (second != null) {
                second.join();
            }
        }
    }

    private static FutureTask<Void> closeTask(EventSubscription<?> subscription) {
        return new FutureTask<>(() -> {
            subscription.close();
            return null;
        });
    }
}
