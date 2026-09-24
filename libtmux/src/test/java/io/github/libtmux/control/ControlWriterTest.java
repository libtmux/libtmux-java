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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            if (answer(holder.get(), line)) {
                synchronized (lines) {
                    lines.add(line);
                }
            }
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
    void aQueuedRequestReportsItsWaitApartFromItsRun() throws Exception {
        AtomicReference<ControlWriter> holder = new AtomicReference<>();
        GatedReplyingWriter output = new GatedReplyingWriter(line -> answer(holder.get(), line));
        ControlWriter writer = writer(output, 2, ignored -> {});
        holder.set(writer);
        writer.start();
        Thread first = exchange(writer, "first", PATIENCE, new AtomicReference<>());
        assertTrue(output.firstEntered.await(1, TimeUnit.SECONDS));
        AtomicReference<long[]> timing = new AtomicReference<>();
        Thread second = Thread.ofVirtual().start(() -> {
            writer.exchange("second", PATIENCE);
            timing.set(writer.takeTiming(-1));
        });
        Thread.sleep(200);
        output.releaseFirst.countDown();
        first.join(1_000);
        second.join(1_000);
        writer.close();
        writer.join(1_000);

        long queued = timing.get()[0];
        long ran = timing.get()[1];
        assertTrue(queued >= TimeUnit.MILLISECONDS.toNanos(150), "queued only " + queued + " ns");
        assertTrue(ran < queued, "the run time " + ran + " ns includes the " + queued + " ns queued");
    }

    /** A block a hook ran carries tmux's clear flag, and answers no request however it arrives. */
    @Test
    void aHookBlockDoesNotAnswerAWaitingRequest() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        GatedReplyingWriter output = new GatedReplyingWriter(line -> {
            if (!MARKER.matcher(line).matches()) {
                written.countDown();
            }
        });
        output.releaseFirst.countDown();
        ControlWriter writer = writer(output, 1, ignored -> {});
        writer.start();
        FutureTask<ControlReply> waiting = new FutureTask<>(() -> writer.exchange("request", PATIENCE));
        Thread.ofVirtual().start(waiting);
        assertTrue(written.await(1, TimeUnit.SECONDS));

        writer.complete(OperationOutcome.COMPLETE, List.of("hooked"), false);
        writer.complete(OperationOutcome.COMPLETE, List.of("answer"), true);

        assertEquals(List.of("answer"), waiting.get(1, TimeUnit.SECONDS).lines());
        writer.close();
        writer.join(1_000);
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

    private static final Pattern MARKER = Pattern.compile("^'display-message' '-p' '(libtmux-reply-[^']+)'$");

    /**
     * Answers one written line as tmux does: a request with a block of its own text, and the marker
     * line behind it with the marker, which ends that request's reply.
     *
     * @return whether the line was a request rather than a marker
     */
    private static boolean answer(ControlWriter writer, String line) {
        Matcher marker = MARKER.matcher(line);
        if (marker.matches()) {
            writer.complete(OperationOutcome.COMPLETE, List.of(marker.group(1)), true);
            return false;
        }
        writer.complete(OperationOutcome.COMPLETE, List.of(line), true);
        return true;
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
            // A request and its marker line can arrive in one write; tmux answers each line.
            new String(data, offset, length)
                    .lines()
                    .filter(line -> !line.isEmpty())
                    .forEach(written);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
