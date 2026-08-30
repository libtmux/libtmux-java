package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTransportException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Admission, attribution, and shutdown at the control writer's concurrency boundary. */
final class ControlWriterTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Test
    void aFullQueueTimesOutBeforeDispatch() throws Exception {
        BlockingWriter output = new BlockingWriter();
        ControlWriter writer = writer(output, 1, ignored -> {});
        writer.start();
        Thread active = exchange(writer, "active", PATIENCE, new AtomicReference<>());
        assertTrue(output.entered.await(1, TimeUnit.SECONDS));
        AtomicReference<TmuxTransportException> queuedFailure = new AtomicReference<>();
        Thread queued = exchange(writer, "queued", PATIENCE, queuedFailure);
        Thread.sleep(100);

        TmuxTransportException refused =
                assertThrows(TmuxTransportException.class, () -> writer.exchange("refused", Duration.ofMillis(100)));

        assertEquals(DispatchOutcome.NOT_DISPATCHED, refused.outcome());
        writer.close();
        output.release.countDown();
        writer.join(1_000);
        active.join(1_000);
        queued.join(1_000);
        assertEquals(DispatchOutcome.NOT_DISPATCHED, queuedFailure.get().outcome());
    }

    @Test
    void aWriteFailureIsUncertainAndEndsTheActor() throws Exception {
        AtomicReference<TmuxTransportException> actorFailure = new AtomicReference<>();
        ControlWriter writer = writer(new FailingWriter(), 1, actorFailure::set);
        writer.start();

        TmuxTransportException failure =
                assertThrows(TmuxTransportException.class, () -> writer.exchange("command", PATIENCE));

        assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
        writer.join(1_000);
        assertEquals(DispatchOutcome.UNKNOWN, actorFailure.get().outcome());
    }

    @Test
    void oneDeadlineIncludesAWriteThatNeverReturns() throws Exception {
        BlockingWriter output = new BlockingWriter();
        ControlWriter writer = writer(output, 1, ignored -> {});
        writer.start();
        long started = System.nanoTime();

        TmuxTransportException failure =
                assertThrows(TmuxTransportException.class, () -> writer.exchange("active", Duration.ofMillis(100)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
        assertTrue(elapsedMillis < 1_000, "a write added a second timeout: " + elapsedMillis + " ms");
        writer.join(1_000);
    }

    @Test
    void failureCleanupRunsBeforeTheWriterCanCloseProcessInput() throws Exception {
        BlockingWriter output = new BlockingWriter();
        AtomicReference<Boolean> closedBeforeCleanup = new AtomicReference<>();
        ControlWriter writer = writer(output, 1, ignored -> {
            try {
                closedBeforeCleanup.set(output.closed.await(200, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closedBeforeCleanup.set(true);
            }
        });
        writer.start();

        assertThrows(TmuxTransportException.class, () -> writer.exchange("active", Duration.ofMillis(100)));

        writer.join(1_000);
        assertFalse(closedBeforeCleanup.get(), "the writer closed process input before failure cleanup began");
    }

    @Test
    void closeDistinguishesPickedFromQueuedRequests() throws Exception {
        BlockingWriter output = new BlockingWriter();
        ControlWriter writer = writer(output, 1, ignored -> {});
        writer.start();
        AtomicReference<TmuxTransportException> activeFailure = new AtomicReference<>();
        AtomicReference<TmuxTransportException> queuedFailure = new AtomicReference<>();
        Thread active = exchange(writer, "active", PATIENCE, activeFailure);
        assertTrue(output.entered.await(1, TimeUnit.SECONDS));
        Thread queued = exchange(writer, "queued", PATIENCE, queuedFailure);
        Thread.sleep(100);

        writer.close();
        output.release.countDown();
        writer.join(1_000);
        active.join(1_000);
        queued.join(1_000);

        assertEquals(DispatchOutcome.UNKNOWN, activeFailure.get().outcome());
        assertEquals(DispatchOutcome.NOT_DISPATCHED, queuedFailure.get().outcome());
        assertEquals(
                DispatchOutcome.NOT_DISPATCHED,
                assertThrows(TmuxTransportException.class, () -> writer.exchange("late", PATIENCE))
                        .outcome());
    }

    @Test
    void readerDeathDistinguishesPickedFromQueuedRequests() throws Exception {
        BlockingWriter output = new BlockingWriter();
        ControlWriter writer = writer(output, 1, ignored -> {});
        writer.start();
        AtomicReference<TmuxTransportException> activeFailure = new AtomicReference<>();
        AtomicReference<TmuxTransportException> queuedFailure = new AtomicReference<>();
        Thread active = exchange(writer, "active", PATIENCE, activeFailure);
        assertTrue(output.entered.await(1, TimeUnit.SECONDS));
        Thread queued = exchange(writer, "queued", PATIENCE, queuedFailure);
        Thread.sleep(100);

        writer.readerEnded();
        output.release.countDown();
        writer.join(1_000);
        active.join(1_000);
        queued.join(1_000);

        assertEquals(DispatchOutcome.UNKNOWN, activeFailure.get().outcome());
        assertEquals(DispatchOutcome.NOT_DISPATCHED, queuedFailure.get().outcome());
    }

    @Test
    void concurrentRequestsKeepAdmissionOrder() throws Exception {
        AtomicReference<ControlWriter> holder = new AtomicReference<>();
        List<String> lines = new ArrayList<>();
        GatedReplyingWriter output = new GatedReplyingWriter(line -> {
            synchronized (lines) {
                lines.add(line);
            }
            holder.get().complete(OperationOutcome.COMPLETE, List.of(line));
        });
        ControlWriter writer = writer(output, 3, ignored -> {});
        holder.set(writer);
        writer.start();
        AtomicReference<TmuxTransportException> firstFailure = new AtomicReference<>();
        AtomicReference<TmuxTransportException> secondFailure = new AtomicReference<>();
        AtomicReference<TmuxTransportException> thirdFailure = new AtomicReference<>();
        Thread first = exchange(writer, "first", PATIENCE, firstFailure);
        assertTrue(output.firstEntered.await(1, TimeUnit.SECONDS));
        Thread second = exchange(writer, "second", PATIENCE, secondFailure);
        Thread.sleep(100);
        Thread third = exchange(writer, "third", PATIENCE, thirdFailure);
        Thread.sleep(100);
        output.releaseFirst.countDown();

        first.join(1_000);
        second.join(1_000);
        third.join(1_000);
        writer.close();
        writer.join(1_000);

        assertEquals(List.of("first", "second", "third"), lines);
        assertFalse(firstFailure.get() != null || secondFailure.get() != null || thirdFailure.get() != null);
    }

    @Test
    void interruptionPreservesCertaintyAndTheInterruptFlag() throws Exception {
        BlockingWriter output = new BlockingWriter();
        ControlWriter writer = writer(output, 1, ignored -> {});
        writer.start();
        AtomicReference<TmuxTransportException> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread request = Thread.ofVirtual().start(() -> {
            try {
                writer.exchange("active", PATIENCE);
            } catch (TmuxTransportException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        assertTrue(output.entered.await(1, TimeUnit.SECONDS));

        request.interrupt();
        request.join(1_000);
        output.release.countDown();
        writer.join(1_000);

        assertEquals(DispatchOutcome.UNKNOWN, failure.get().outcome());
        assertTrue(interrupted.get());
    }

    private static ControlWriter writer(Writer output, int capacity, Consumer<TmuxTransportException> failed) {
        return new ControlWriter(new BufferedWriter(output), capacity, failed);
    }

    private static Thread exchange(
            ControlWriter writer, String line, Duration timeout, AtomicReference<TmuxTransportException> failure) {
        return Thread.ofVirtual().start(() -> {
            try {
                writer.exchange(line, timeout);
            } catch (TmuxTransportException e) {
                failure.set(e);
            }
        });
    }

    private static class BlockingWriter extends Writer {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(char[] data, int offset, int length) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            closed.countDown();
            release.countDown();
        }
    }

    private static final class FailingWriter extends Writer {

        @Override
        public void write(char[] data, int offset, int length) throws IOException {
            throw new IOException("broken pipe");
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static final class GatedReplyingWriter extends Writer {

        private final Consumer<String> written;
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private boolean first = true;

        GatedReplyingWriter(Consumer<String> written) {
            this.written = written;
        }

        @Override
        public void write(char[] data, int offset, int length) throws IOException {
            if (first) {
                first = false;
                firstEntered.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            written.accept(new String(data, offset, length).stripTrailing());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
