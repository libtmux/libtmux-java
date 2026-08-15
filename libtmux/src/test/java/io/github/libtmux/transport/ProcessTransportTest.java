package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The contract every caller depends on, driven with an ordinary child rather than tmux.
 *
 * <p>Nothing here is about tmux specifically: it is about owning a child process honestly. A call
 * either produces an exact result, or it fails saying how certain it is that the command ran. No
 * child ever outlives the call that started it.
 */
final class ProcessTransportTest {

    private static final Duration GENEROUS = Duration.ofSeconds(30);
    private static final int FLOOD_BYTES = 262_144;

    private static CommandRequest shell(String script, Duration timeout) {
        return new CommandRequest(List.of("/bin/sh"), List.of("-c", script), timeout);
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
                    transport.execute(new CommandRequest(List.of("/bin/echo"), List.of("left;right"), GENEROUS));

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
                    () -> transport.execute(new CommandRequest(List.of("/nonexistent/tmux"), List.of("ls"), GENEROUS)));

            assertEquals(
                    DispatchOutcome.NOT_DISPATCHED, failure.outcome(), "nothing ran, so the caller may retry freely");
        }
    }

    // --------------------------------------------------------------------------------- deadlines

    @Test
    void aChildThatOutlivesItsDeadlineIsKilledAndReportedUnknown() {
        try (ProcessTransport transport = new ProcessTransport()) {
            TmuxTransportException failure = assertThrows(
                    TmuxTransportException.class, () -> transport.execute(shell("sleep 30", Duration.ofMillis(250))));

            assertEquals(
                    DispatchOutcome.UNKNOWN,
                    failure.outcome(),
                    "tmux may already have applied the command before it hung");
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

    // ---------------------------------------------------------------------------- process hygiene

    /**
     * The probe proves itself before it is trusted: a gate that cannot observe a live child would
     * report every leak as clean.
     */
    @Test
    void noChildOutlivesTheCallThatStartedIt() throws Exception {
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
