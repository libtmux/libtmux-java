package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract every caller depends on, driven with an ordinary child rather than tmux.
 *
 * <p>The transport owns its direct process and the descendants visible when cleanup starts. A call
 * either produces an exact result, or it fails saying how certain it is that the command ran.
 */
final class ProcessTransportTest {

    private static final Duration GENEROUS = Duration.ofSeconds(30);
    private static final int FLOOD_BYTES = 262_144;

    private static CommandRequest shell(String script, Duration timeout) {
        return CommandRequest.of(List.of("/bin/sh"), List.of("-c", script), timeout);
    }

    private static CommandRequest bash(String script, Duration timeout) {
        return CommandRequest.of(List.of("/bin/bash"), List.of("-c", script), timeout);
    }

    // ------------------------------------------------------------------ channels and exit status

    @Test
    void eachChannelArrivesWhole() {
        try (ProcessTransport transport = new ProcessTransport()) {
            CommandResult result = transport.execute(shell("echo out; echo err >&2", GENEROUS));

            assertEquals(List.of("out"), result.stdout());
            assertEquals(List.of("err"), result.stderr());
            assertEquals(0, result.exitCode());
        }
    }

    @Test
    void aNonzeroExitIsDataAndKeepsWhateverOutputArrived() {
        try (ProcessTransport transport = new ProcessTransport()) {
            CommandResult result = transport.execute(shell("echo out; echo err >&2; exit 3", GENEROUS));

            assertEquals(3, result.exitCode());
            assertFalse(result.succeeded());
            assertEquals(List.of("out"), result.stdout());
            assertEquals(List.of("err"), result.stderr());
        }
    }

    @Test
    void decodingHoldsThroughARealChildNotJustTheDecoder() {
        try (ProcessTransport transport = new ProcessTransport()) {
            assertEquals(
                    List.of("a\\xff"),
                    transport.execute(shell("printf 'a\\377'", GENEROUS)).stdout(),
                    "a byte that is not UTF-8 stays recoverable");
            assertEquals(
                    List.of("a", "b"),
                    transport
                            .execute(shell("printf 'a\\r\\nb\\r\\n'", GENEROUS))
                            .stdout(),
                    "universal newlines");
            assertEquals(
                    List.of("alpha", "", "beta"),
                    transport
                            .execute(shell("printf 'alpha\\n\\nbeta\\n'", GENEROUS))
                            .stdout(),
                    "an interior blank line survives");
            assertEquals(List.of(), transport.execute(shell("true", GENEROUS)).stdout(), "silence is an empty list");
        }
    }

    // ------------------------------------------------------------------------------ argv is argv

    @Test
    void aSemicolonInsideOneArgumentIsNeverASeparator() {
        try (ProcessTransport transport = new ProcessTransport()) {
            CommandResult result =
                    transport.execute(CommandRequest.of(List.of("/bin/echo"), List.of("left;right"), GENEROUS));

            assertEquals(List.of("left;right"), result.stdout());
        }
    }

    /**
     * POSIX {@code execve} takes NUL-terminated strings, so an argument containing one cannot
     * survive the boundary intact. Rejecting it late would mean running a truncated command.
     */
    @Test
    void anEmbeddedNulIsRejectedBeforeAnythingStarts() {
        try (ProcessTransport transport = new ProcessTransport(2)) {
            CommandRequest poisoned = shell("echo \0 hi", GENEROUS);

            for (int attempt = 0; attempt < 6; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> transport.execute(poisoned));
            }

            assertEquals(
                    List.of("still working"),
                    transport.execute(shell("echo 'still working'", GENEROUS)).stdout(),
                    "a rejected request must not consume the permit it never used");
        }
    }

    @Test
    void anExecutableThatDoesNotExistIsNotDispatched() {
        try (ProcessTransport transport = new ProcessTransport()) {
            TmuxTransportException failure = assertThrows(
                    TmuxTransportException.class,
                    () -> transport.execute(CommandRequest.of(List.of("/nonexistent/tmux"), List.of("ls"), GENEROUS)));

            assertEquals(
                    DispatchOutcome.NOT_DISPATCHED, failure.outcome(), "nothing ran, so the caller may retry freely");
        }
    }

    // --------------------------------------------------------------------------------- deadlines

    @Test
    void aChildThatOutlivesItsDeadlineIsKilledAndReportedUnknown() {
        try (ProcessTransport transport = new ProcessTransport()) {
            TmuxTimeoutException failure = assertThrows(
                    TmuxTimeoutException.class, () -> transport.execute(shell("sleep 30", Duration.ofMillis(250))));

            assertEquals(
                    DispatchOutcome.UNKNOWN,
                    failure.outcome(),
                    "tmux may already have applied the command before it hung");
        }
    }

    @Test
    void blockedStandardInputObeysTheDeadlineAndReturnsItsPermit() throws Exception {
        AtomicReference<Process> child = new AtomicReference<>();
        ProcessTransport.ProcessStarter starter = command -> {
            Process started = new ProcessBuilder(command).start();
            child.set(started);
            return started;
        };
        ProcessTransport transport = new ProcessTransport(1, 1_024, starter, System::nanoTime);
        FutureTask<CommandResult> request = new FutureTask<>(() -> transport.execute(CommandRequest.of(
                List.of("/bin/sh"), List.of("-c", "sleep 30"), Duration.ofMillis(250), "x".repeat(1_048_576))));
        Thread caller = Thread.ofVirtual().start(request);

        try {
            ExecutionException ended = assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS));
            TmuxTimeoutException failure = assertInstanceOf(TmuxTimeoutException.class, ended.getCause());
            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
            assertFalse(child.get().isAlive(), "the child survived its input deadline");
            assertEquals(
                    List.of("reclaimed"),
                    transport
                            .execute(shell("echo reclaimed", Duration.ofSeconds(2)))
                            .stdout(),
                    "blocked input permanently consumed the only permit");
        } finally {
            transport.close();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(caller.isAlive(), "the blocked input caller did not stop");
        }
    }

    @Test
    void aHugePositiveTimeoutDoesNotOverflowTheDeadline() {
        try (ProcessTransport transport = new ProcessTransport()) {
            CommandResult result = transport.execute(shell("echo saturated", Duration.ofSeconds(Long.MAX_VALUE)));

            assertEquals(List.of("saturated"), result.stdout());
        }
    }

    @Test
    void aRequestExpiredBeforeAdmissionNeverCrossesTheProcessBoundary() {
        AtomicInteger clockReads = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        ProcessTransport.ProcessStarter starter = command -> {
            starts.incrementAndGet();
            return new ProcessBuilder(command).start();
        };
        try (ProcessTransport transport =
                new ProcessTransport(1, 1_024, starter, () -> clockReads.getAndIncrement() == 0 ? 10L : 12L)) {
            TmuxTimeoutException failure = assertThrows(
                    TmuxTimeoutException.class,
                    () -> transport.execute(shell("echo must-not-run", Duration.ofNanos(1))));

            assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
            assertEquals(0, starts.get(), "an already-expired request reached Process.start");
        }
    }

    @Test
    void outputBeyondTheConfiguredChannelLimitEndsTheChildAndReclaimsTheTransport() {
        try (ProcessTransport transport = new ProcessTransport(1, 1_024)) {
            TmuxTransportException failure = assertThrows(
                    TmuxTransportException.class,
                    () -> transport.execute(shell("head -c 4096 /dev/zero | tr '\\0' x", GENEROUS)));

            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
            assertTrue(String.valueOf(failure.getMessage()).contains("1024 byte channel limit"));
            assertEquals(
                    List.of("reclaimed"),
                    transport.execute(shell("echo reclaimed", GENEROUS)).stdout());
        }
    }

    @Test
    void outputOverflowIsReportedBeforeATermIgnoringChildsDeadline() {
        try (ProcessTransport transport = new ProcessTransport(1, 1_024)) {
            long started = System.nanoTime();

            TmuxTransportException failure = assertThrows(
                    TmuxTransportException.class,
                    () -> transport.execute(
                            bash("trap '' TERM; while :; do printf 1234567890; done", Duration.ofSeconds(5))));

            assertFalse(failure instanceof TmuxTimeoutException, "the pump observed overflow before the deadline");
            assertTrue(String.valueOf(failure.getMessage()).contains("1024 byte channel limit"));
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0,
                    "overflow was not acted on promptly");
        }
    }

    @Test
    void aPipeCloseFailureCannotBeReportedAsSuccess() {
        StubProcess process = new StubProcess(new FailingCloseInputStream());
        process.finish();

        try (ProcessTransport transport = new ProcessTransport(1, 1_024, command -> process, System::nanoTime)) {
            TmuxTransportException failure =
                    assertThrows(TmuxTransportException.class, () -> transport.execute(shell("ignored", GENEROUS)));

            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
        }
    }

    @Test
    void outputOverflowReclaimsDescendantsBeforeTheRootCanDisappear(@TempDir Path directory) throws Exception {
        Path descendantPid = directory.resolve("descendant.pid");
        String script = "(trap '' HUP TERM; echo \"$BASHPID\" > \"$1.tmp\"; "
                + "mv \"$1.tmp\" \"$1\"; exec sleep 30) </dev/null >/dev/null 2>&1 & "
                + "while [ ! -f \"$1\" ]; do :; done; "
                + "while :; do printf 1234567890; done";
        long descendant = -1;
        CommandRequest request = CommandRequest.of(
                List.of("/bin/bash"), List.of("-c", script, "probe", descendantPid.toString()), GENEROUS);

        try (ProcessTransport transport = new ProcessTransport(1, 1_024)) {
            assertThrows(TmuxTransportException.class, () -> transport.execute(request));
            assertTrue(awaitFile(descendantPid), "the overflowing process never started its descendant");
            descendant = Long.parseLong(Files.readString(descendantPid).trim());

            assertTrue(awaitDead(descendant), "output overflow orphaned a descendant of the killed process");
        } finally {
            if (descendant > 0) {
                ProcessHandle.of(descendant).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /** A tmux command may start its durable server only after cleanup has begun. */
    @Test
    void cleanupDoesNotAdoptADescendantSpawnedAfterItsOwnershipSnapshot(@TempDir Path directory) throws Exception {
        Path descendantPid = directory.resolve("detached.pid");
        Path ready = directory.resolve("ready");
        String script = "sleep 1; trap '(trap \"\" HUP TERM; echo \"$BASHPID\" > \"$1.tmp\"; "
                + "mv \"$1.tmp\" \"$1\"; exec sleep 30) </dev/null >/dev/null 2>&1 & "
                + "while :; do :; done' TERM; "
                + "ready=0; while :; do sleep 30 & child=$!; "
                + "if [ \"$ready\" = 0 ]; then echo ready > \"$2.tmp\"; mv \"$2.tmp\" \"$2\"; ready=1; fi; "
                + "wait \"$child\"; done";
        ProcessTransport.ProcessStarter starter = command -> {
            Process started = new ProcessBuilder(command).start();
            boolean armed = false;
            try {
                armed = awaitFile(ready);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while arming the cleanup fixture", e);
            } finally {
                if (!armed) {
                    started.descendants().forEach(ProcessHandle::destroyForcibly);
                    started.destroyForcibly();
                }
            }
            if (!armed) {
                throw new IOException("cleanup fixture did not arm");
            }
            return started;
        };
        CommandRequest request = CommandRequest.of(
                List.of("/bin/bash"),
                List.of("-c", script, "probe", descendantPid.toString(), ready.toString()),
                Duration.ofMillis(250));
        long descendant = -1;

        try (ProcessTransport transport = new ProcessTransport(1, 1_024, starter, System::nanoTime)) {
            assertThrows(TmuxTransportException.class, () -> transport.execute(request));
            assertTrue(awaitFile(descendantPid), "the cleanup-time descendant never started");
            descendant = Long.parseLong(Files.readString(descendantPid).trim());

            assertTrue(
                    ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false),
                    "cleanup adopted a descendant created after its ownership snapshot");
        } finally {
            if (descendant > 0) {
                ProcessHandle.of(descendant).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void anInterruptedCallerReportsUnknownAndKeepsItsInterrupt() throws InterruptedException {
        try (ProcessTransport transport = new ProcessTransport()) {
            BlockingQueue<Object> outcome = new ArrayBlockingQueue<>(1);
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    outcome.add(transport.execute(shell("sleep 30", GENEROUS)));
                } catch (RuntimeException e) {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                    outcome.add(e);
                }
            });

            caller.start();
            Thread.sleep(400);
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(20));

            Object result = outcome.poll(10, TimeUnit.SECONDS);
            TmuxTransportException failure =
                    assertInstanceOf(TmuxTransportException.class, result, "an interrupt is not a tmux answer");
            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
            assertTrue(interruptRestored.get(), "swallowing the interrupt would strand the caller's canceller");
        }
    }

    // ------------------------------------------------------------------------------------- close

    @Test
    void useAfterCloseIsAProgrammerError() {
        ProcessTransport transport = new ProcessTransport();
        transport.close();

        assertThrows(IllegalStateException.class, () -> transport.execute(shell("echo hi", GENEROUS)));
    }

    @Test
    void closeIsIdempotent() {
        ProcessTransport transport = new ProcessTransport();

        transport.close();
        transport.close();
    }

    /**
     * A caller parked in the process wait when close kills its child would otherwise receive an
     * ordinary result carrying exit 137 — indistinguishable from tmux itself dying on a signal, and
     * therefore impossible to act on.
     */
    @Test
    void closeKillingARunningChildReportsUnknownRatherThanASignalExit() throws InterruptedException {
        ProcessTransport transport = new ProcessTransport();
        BlockingQueue<Object> outcome = new ArrayBlockingQueue<>(1);
        Thread caller = new Thread(() -> {
            try {
                outcome.add(transport.execute(shell("sleep 30", GENEROUS)));
            } catch (RuntimeException e) {
                outcome.add(e);
            }
        });

        caller.start();
        Thread.sleep(400);
        transport.close();
        caller.join(TimeUnit.SECONDS.toMillis(20));

        Object result = outcome.poll(10, TimeUnit.SECONDS);
        TmuxTransportException failure = assertInstanceOf(
                TmuxTransportException.class, result, "our own kill must not be reported as tmux's exit status");
        assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
    }

    @Test
    void concurrentCloseWaitsForTheFirstCloseToFinish() throws Exception {
        GatedInputStream stdout = new GatedInputStream();
        StubProcess process = new StubProcess(stdout);
        ProcessTransport transport = new ProcessTransport(1, 1_024, command -> process, System::nanoTime);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CommandResult> request = callers.submit(() -> transport.execute(shell("ignored", GENEROUS)));
            assertTrue(stdout.readStarted.await(5, TimeUnit.SECONDS), "the request never reached its pipe read");

            Future<?> firstClose = callers.submit(transport::close);
            assertTrue(process.destroyed.await(5, TimeUnit.SECONDS), "the first close never began cleanup");
            Future<?> secondClose = callers.submit(transport::close);

            boolean returnedBeforeCleanup;
            try {
                secondClose.get(100, TimeUnit.MILLISECONDS);
                returnedBeforeCleanup = true;
            } catch (TimeoutException expected) {
                returnedBeforeCleanup = false;
            } finally {
                stdout.release();
            }

            firstClose.get(10, TimeUnit.SECONDS);
            secondClose.get(10, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> request.get(10, TimeUnit.SECONDS));
            assertFalse(returnedBeforeCleanup, "a concurrent close returned while cleanup was still running");
        } finally {
            stdout.release();
            transport.close();
        }
    }

    @Test
    void interruptedCloseCannotReturnBeforeAnAdmittedLaunchIsPublished() throws Exception {
        CountDownLatch launchEntered = new CountDownLatch(1);
        CountDownLatch releaseLaunch = new CountDownLatch(1);
        StubProcess process = new StubProcess(new ByteArrayInputStream(new byte[0]));
        ProcessTransport.ProcessStarter starter = command -> {
            launchEntered.countDown();
            try {
                releaseLaunch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("launch interrupted", e);
            }
            return process;
        };
        ProcessTransport transport = new ProcessTransport(1, 1_024, starter, System::nanoTime);
        FutureTask<CommandResult> request = new FutureTask<>(() -> transport.execute(shell("ignored", GENEROUS)));
        Thread caller = Thread.ofVirtual().start(request);
        BlockingQueue<Object> closeOutcome = new ArrayBlockingQueue<>(1);

        try {
            assertTrue(launchEntered.await(5, TimeUnit.SECONDS), "the launch seam was never entered");
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    transport.close();
                    closeOutcome.add("closed");
                } catch (RuntimeException e) {
                    closeOutcome.add(e);
                }
            });
            assertTrue(awaitClosed(transport), "close never barred new requests");

            closer.interrupt();
            Object earlyOutcome = closeOutcome.poll(100, TimeUnit.MILLISECONDS);
            releaseLaunch.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(10));
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertNull(earlyOutcome, "close returned before the admitted launch was published");
            assertEquals("closed", closeOutcome.poll(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> request.get(10, TimeUnit.SECONDS));
        } finally {
            releaseLaunch.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            transport.close();
        }
    }

    @Test
    void closeIsBoundedWhenADescendantInheritsTheChildPipes(@TempDir Path directory) throws Exception {
        Path descendantPid = directory.resolve("descendant.pid");
        String script = "trap 'exit 0' TERM; "
                + "(trap '' HUP TERM; echo \"$BASHPID\" > \"$1.tmp\"; "
                + "mv \"$1.tmp\" \"$1\"; exec sleep 30) & wait";
        CommandRequest request = CommandRequest.of(
                List.of("/bin/bash"), List.of("-c", script, "probe", descendantPid.toString()), GENEROUS);
        ProcessTransport transport = new ProcessTransport();

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CommandResult> running = callers.submit(() -> transport.execute(request));
            assertTrue(awaitFile(descendantPid), "the pipe-inheriting descendant never started");
            ProcessHandle descendant = ProcessHandle.of(
                            Long.parseLong(Files.readString(descendantPid).trim()))
                    .orElseThrow();
            Future<?> closing = callers.submit(transport::close);

            boolean bounded;
            try {
                closing.get(2, TimeUnit.SECONDS);
                bounded = true;
            } catch (TimeoutException expected) {
                bounded = false;
            } finally {
                descendant.destroyForcibly();
                descendant.onExit().get(10, TimeUnit.SECONDS);
            }

            closing.get(10, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> running.get(10, TimeUnit.SECONDS));
            assertTrue(bounded, "close waited for an inherited pipe writer instead of closing its read ends");
        } finally {
            transport.close();
        }
    }

    // ------------------------------------------------------------------------------- concurrency

    @Test
    void moreCallersThanPermitsAllFinishWithBothPipesFloodedPastCapacity() throws Exception {
        int bound = 2;
        int callers = bound + 1;
        String flood = "head -c " + FLOOD_BYTES + " /dev/zero | tr '\\0' a &" + " head -c " + FLOOD_BYTES
                + " /dev/zero | tr '\\0' b >&2; wait";

        try (ProcessTransport transport = new ProcessTransport(bound)) {
            ExecutorService callerPool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<CommandResult>> pending = new ArrayList<>();
                for (int index = 0; index < callers; index++) {
                    pending.add(callerPool.submit(() -> transport.execute(shell(flood, GENEROUS))));
                }

                for (Future<CommandResult> future : pending) {
                    CommandResult result = future.get(60, TimeUnit.SECONDS);

                    assertEquals(0, result.exitCode());
                    assertEquals(FLOOD_BYTES, result.stdout().get(0).length(), "stdout arrived whole");
                    assertEquals(FLOOD_BYTES, result.stderr().get(0).length(), "stderr arrived whole");
                }
            } finally {
                callerPool.shutdownNow();
            }
        }
    }

    @Test
    void admissionTimeoutIsTypedAndKnownNotDispatched(@TempDir Path directory) throws Exception {
        ProcessTransport transport = new ProcessTransport(1);
        try {
            Path started = directory.resolve("started");
            CommandRequest occupying = CommandRequest.of(
                    List.of("/bin/sh"),
                    List.of("-c", "touch \"$1\"; while :; do :; done", "probe", started.toString()),
                    GENEROUS);
            FutureTask<CommandResult> first = new FutureTask<>(() -> transport.execute(occupying));
            Thread caller = Thread.ofVirtual().start(first);
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!Files.exists(started) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(Files.exists(started), "the first request never occupied admission");

                TmuxTimeoutException failure = assertThrows(
                        TmuxTimeoutException.class,
                        () -> transport.execute(shell("echo never-started", Duration.ofMillis(50))));

                assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
            } finally {
                transport.close();
                caller.join();
                assertThrows(java.util.concurrent.ExecutionException.class, first::get);
            }
        } finally {
            transport.close();
        }
    }

    @Test
    void waitingRequestsReserveOneProcessForOrdinaryWork() throws Exception {
        int bound = 4;
        List<GatedInputStream> blocked = java.util.stream.IntStream.range(0, bound - 1)
                .mapToObj(ignored -> new GatedInputStream())
                .toList();
        AtomicInteger starts = new AtomicInteger();
        ProcessTransport.ProcessStarter starter = command -> {
            int index = starts.getAndIncrement();
            return index < blocked.size() ? new StubProcess(blocked.get(index)) : new ProcessBuilder(command).start();
        };
        ProcessTransport transport = new ProcessTransport(bound, 1_024, starter, System::nanoTime);
        ExecutorService callers = Executors.newFixedThreadPool(bound - 1);
        List<Future<CommandResult>> waiting = new ArrayList<>();
        try {
            for (int index = 0; index < bound - 1; index++) {
                waiting.add(callers.submit(() -> transport.executeWaiting(shell("ignored", GENEROUS))));
            }
            for (GatedInputStream output : blocked) {
                assertTrue(output.readStarted.await(5, TimeUnit.SECONDS), "a waiting request never started");
            }

            TmuxTransportException refused = assertThrows(
                    TmuxTransportException.class,
                    () -> transport.executeWaiting(shell("echo should-not-start", Duration.ofMillis(50))));

            assertEquals(TmuxTransportException.class, refused.getClass(), "a full wait lane queued to its timeout");
            assertEquals("waiting capacity is full; retry after another wait ends", refused.getMessage());
            assertEquals(DispatchOutcome.NOT_DISPATCHED, refused.outcome());
            assertEquals(bound - 1, starts.get(), "a fourth waiting process crossed the reserved boundary");
            assertEquals(
                    List.of("ordinary"),
                    transport
                            .execute(shell("printf ordinary", Duration.ofSeconds(2)))
                            .stdout(),
                    "ordinary work could not use the reserved process");
        } finally {
            blocked.forEach(GatedInputStream::release);
            transport.close();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "waiting callers did not stop");
            assertTrue(waiting.stream().allMatch(Future::isDone), "a waiting call remained incomplete");
        }
    }

    @Test
    void aSingleProcessTransportRefusesAWaitBeforeDispatch() {
        AtomicInteger starts = new AtomicInteger();
        ProcessTransport.ProcessStarter starter = command -> {
            starts.incrementAndGet();
            return new ProcessBuilder(command).start();
        };
        try (ProcessTransport transport = new ProcessTransport(1, 1_024, starter, System::nanoTime)) {
            TmuxTransportException refused = assertThrows(
                    TmuxTransportException.class,
                    () -> transport.executeWaiting(shell("echo should-not-start", Duration.ofSeconds(1))));

            assertEquals(DispatchOutcome.NOT_DISPATCHED, refused.outcome());
            assertEquals(0, starts.get(), "the impossible waiting request was dispatched");
        }
    }

    @Test
    void aWaitingCallAdmittedBeforeCloseKeepsItsDispatchCertainty() throws Exception {
        int bound = 2;
        List<GatedInputStream> blocked = List.of(new GatedInputStream(), new GatedInputStream());
        AtomicInteger starts = new AtomicInteger();
        ProcessTransport.ProcessStarter starter = command -> {
            int index = starts.getAndIncrement();
            return new StubProcess(blocked.get(index));
        };
        ProcessTransport transport = new ProcessTransport(bound, 1_024, starter, System::nanoTime);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        List<Future<CommandResult>> occupying = new ArrayList<>();
        Future<CommandResult> waiting = null;
        Thread waitingThread = null;
        Future<?> closing = null;
        try {
            for (int index = 0; index < bound; index++) {
                occupying.add(callers.submit(() -> transport.execute(shell("ignored", GENEROUS))));
            }
            for (GatedInputStream output : blocked) {
                assertTrue(output.readStarted.await(5, TimeUnit.SECONDS), "an occupying request never started");
            }
            FutureTask<CommandResult> admitted =
                    new FutureTask<>(() -> transport.executeWaiting(shell("never-started", GENEROUS)));
            Thread admittedCaller = Thread.ofVirtual().start(admitted);
            waiting = admitted;
            waitingThread = admittedCaller;
            assertTrue(
                    awaitTimedWait(admitted, admittedCaller),
                    "the admitted waiting call never blocked on ordinary admission");

            closing = callers.submit(transport::close);
            assertTrue(awaitClosed(transport), "close never barred new requests");
            blocked.forEach(GatedInputStream::release);

            ExecutionException ended = assertThrows(ExecutionException.class, () -> admitted.get(5, TimeUnit.SECONDS));
            TmuxTransportException failure = assertInstanceOf(TmuxTransportException.class, ended.getCause());
            assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
            assertEquals(bound, starts.get(), "the blocked waiting call reached the process starter");
            closing.get(5, TimeUnit.SECONDS);
        } finally {
            blocked.forEach(GatedInputStream::release);
            transport.close();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "transport callers did not stop");
            if (waitingThread != null) {
                waitingThread.join(TimeUnit.SECONDS.toMillis(10));
                assertFalse(waitingThread.isAlive(), "the admitted waiting caller did not stop");
            }
            assertTrue(occupying.stream().allMatch(Future::isDone), "an occupying call remained incomplete");
            assertTrue(waiting == null || waiting.isDone(), "the admitted waiting call remained incomplete");
            assertTrue(closing == null || closing.isDone(), "transport close remained incomplete");
        }
    }

    @Test
    void interruptedReclamationReturnsItsAdmissionPermit() throws Exception {
        GatedInputStream stdout = new GatedInputStream();
        StubProcess firstProcess = new StubProcess(stdout);
        AtomicInteger starts = new AtomicInteger();
        ProcessTransport.ProcessStarter starter =
                command -> starts.getAndIncrement() == 0 ? firstProcess : new ProcessBuilder(command).start();
        ProcessTransport transport = new ProcessTransport(1, 1_024, starter, System::nanoTime);
        FutureTask<CommandResult> first = new FutureTask<>(() -> transport.execute(shell("ignored", GENEROUS)));
        Thread caller = Thread.ofVirtual().start(first);

        try {
            assertTrue(stdout.readStarted.await(5, TimeUnit.SECONDS), "the first request never began draining");
            caller.interrupt();
            assertTrue(
                    firstProcess.destroyed.await(5, TimeUnit.SECONDS),
                    "the interrupted caller never began process cleanup");
            assertTrue(awaitReclamation(first, caller), "the interrupted caller never reached drain reclamation");
            stdout.release();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
            assertEquals(
                    List.of("reclaimed"),
                    transport
                            .execute(shell("echo reclaimed", Duration.ofSeconds(2)))
                            .stdout(),
                    "the interrupted request permanently consumed the only permit");
        } finally {
            stdout.release();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            transport.close();
        }
    }

    // ---------------------------------------------------------------------------- process hygiene

    @Test
    void anIdleTransportStartsNoPumpThreads() {
        long before = pumpThreads();
        ProcessTransport transport = new ProcessTransport(2);

        try {
            assertEquals(before, pumpThreads(), "an idle transport does not need process-pipe workers");
        } finally {
            transport.close();
        }
    }

    /**
     * The probe proves itself before it is trusted: a gate that cannot observe a live child would
     * report every leak as clean.
     */
    @Test
    void aTimedOutCallReclaimsItsObservableProcessTree() throws Exception {
        String marker = "libtmux-probe-" + UUID.randomUUID();

        Process control = new ProcessBuilder("/bin/sh", "-c", "sleep 30 # " + marker).start();
        try {
            assertTrue(awaitPresent(marker), "the leak probe cannot see a running child, so it proves nothing");
        } finally {
            control.destroyForcibly();
            control.waitFor(20, TimeUnit.SECONDS);
        }
        assertTrue(awaitAbsent(marker), "the control child outlived the probe's own cleanup");

        try (ProcessTransport transport = new ProcessTransport()) {
            assertThrows(
                    TmuxTransportException.class,
                    () -> transport.execute(shell("sleep 30 # " + marker, Duration.ofMillis(250))));

            assertTrue(awaitAbsent(marker), "a child that outran its deadline is still running");
        }
    }

    private static boolean awaitPresent(String marker) throws InterruptedException {
        return await(marker, true);
    }

    private static boolean awaitAbsent(String marker) throws InterruptedException {
        return await(marker, false);
    }

    private static boolean await(String marker, boolean wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (marked(marker).isPresent() == wanted) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static Optional<ProcessHandle> marked(String marker) {
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info().commandLine().orElse("").contains(marker))
                .findAny();
    }

    private static long pumpThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("libtmux-pump-"))
                .count();
    }

    private static boolean awaitDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return Files.exists(file);
    }

    private static boolean awaitClosed(ProcessTransport transport) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                transport.execute(shell("true", Duration.ofMillis(1)));
            } catch (IllegalStateException expected) {
                return true;
            } catch (TmuxTransportException expected) {
                assertEquals(DispatchOutcome.NOT_DISPATCHED, expected.outcome());
            }
            Thread.sleep(1);
        }
        return false;
    }

    private static boolean awaitReclamation(Future<?> request, Thread caller) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (request.isDone() || caller.getState() == Thread.State.TIMED_WAITING) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    private static boolean awaitTimedWait(Future<?> request, Thread caller) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!request.isDone() && System.nanoTime() < deadline) {
            if (caller.getState() == Thread.State.TIMED_WAITING) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    private static final class GatedInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() {
            readStarted.countDown();
            boolean interrupted = false;
            while (released.getCount() > 0) {
                try {
                    released.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return read();
        }

        void release() {
            released.countDown();
        }
    }

    private static final class FailingCloseInputStream extends ByteArrayInputStream {

        FailingCloseInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            throw new IOException("pipe close failed");
        }
    }

    private static final class StubProcess extends Process {
        private final OutputStream stdin = new ByteArrayOutputStream();
        private final InputStream stdout;
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final CompletableFuture<Process> exit = new CompletableFuture<>();

        StubProcess(InputStream stdout) {
            this.stdout = stdout;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            finish();
        }

        @Override
        public Process destroyForcibly() {
            finish();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }

        private void finish() {
            destroyed.countDown();
            if (alive.compareAndSet(true, false)) {
                exited.countDown();
                exit.complete(this);
            }
        }
    }

    @Test
    void aBoundBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessTransport(0));
    }

    @Test
    void aTransportIsUsableAsAResource() {
        try (TmuxTransport transport = new ProcessTransport()) {
            assertNotNull(transport.execute(shell("true", GENEROUS)));
        }
    }
}
