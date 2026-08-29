package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransportException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where one tmux command ends and the next begins, and what a control-mode line makes of that.
 *
 * <p>Every expectation here was measured against tmux rather than derived from this code. The
 * measurements are in {@code docs/spikes/21-command-group-boundaries.md}.
 */
final class ControlClientTest {

    /** A pipe write on the calling virtual thread pins the only carrier and starves the sentinel. */
    @Test
    @Tag("carrier")
    void aBlockedControlWriteDoesNotOccupyTheCallersCarrier(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                sleep 5
                """);

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"))) {
            Thread blocked = Thread.ofVirtual().start(() -> {
                try {
                    client.send(List.of("display-message", "x".repeat(1_048_576)), Duration.ofSeconds(4));
                } catch (RuntimeException expected) {
                    // The fake never answers. Only where the caller blocks matters here.
                }
            });
            Thread.sleep(250);

            CountDownLatch sentinel = new CountDownLatch(1);
            Thread.ofVirtual().start(sentinel::countDown);

            assertTrue(
                    sentinel.await(1, TimeUnit.SECONDS),
                    "the blocked pipe write occupied the only virtual-thread carrier");
            blocked.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    @Test
    void aSemicolonEndingAnArgumentEndsTheCommand() {
        assertTrue(ControlClient.isCommandGroup(List.of("kill-window;", "list-windows")));
        assertTrue(ControlClient.isCommandGroup(List.of("list-windows", ";", "list-panes")));
    }

    /** tmux looks at the end of an argument, so a semicolon anywhere else is just a character. */
    @Test
    void aSemicolonAnywhereElseIsPartOfTheArgument() {
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", "semi;colon")));
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", ";leading")));
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", "plain")));
    }

    @Test
    void aBackslashKeepsTheSemicolonInsteadOfEndingTheCommand() {
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", "trailing\\;")));
    }

    @Test
    void aRejectedCommandGroupDoesNotDiscloseItsArguments(@TempDir Path directory) throws Exception {
        String secret = "pane-secret;";
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                while IFS= read -r request; do :; done
                """);

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"))) {
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> client.send(List.of("display-message", secret)));

            assertFalse(String.valueOf(failure.getMessage()).contains(secret));
        }
    }

    /**
     * The process carrier reaches tmux's argv parser and this one does not, so the backslash that
     * parser would consume is consumed here instead. Passing it on would deliver a different
     * argument than the other carrier did.
     */
    @Test
    void theEscapeGuardingATrailingSemicolonIsSpentRatherThanSent() {
        assertEquals(
                "'display-message' '-p' 'trailing;'",
                ControlClient.line(List.of("display-message", "-p", "trailing\\;")));
    }

    @Test
    void everyOtherArgumentReachesTmuxExactlyAsGiven() {
        assertEquals(
                "'display-message' '-p' 'semi;colon'",
                ControlClient.line(List.of("display-message", "-p", "semi;colon")));
        assertEquals(
                "'display-message' '-p' 'it'\\''s quoted'",
                ControlClient.line(List.of("display-message", "-p", "it's quoted")));
    }

    @Test
    void aTimedOutReplyMakesTheStreamUnavailableForLaterRequests(@TempDir Path directory) throws Exception {
        Path fakeTmux = directory.resolve("tmux");
        Files.writeString(fakeTmux, """
                #!/bin/sh
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                IFS= read -r request
                sleep 1
                """);
        Files.setPosixFilePermissions(fakeTmux, PosixFilePermissions.fromString("rwx------"));
        ServerConfig config = ServerConfig.builder().binary(fakeTmux.toString()).build();

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"));
                EventSubscription<ControlEvent> events = client.subscribeEvents(1)) {
            TmuxTimeoutException failure = assertThrows(
                    TmuxTimeoutException.class, () -> client.send(List.of("list-windows"), Duration.ofMillis(100)));

            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
            assertFalse(client.isAlive(), "a missing reply leaves command attribution uncertain");
            assertTrue(events.isClosed(), "a subscriber cannot wait forever on an unusable client");
            assertThrows(IllegalStateException.class, () -> client.send("list-panes"));
        }
    }

    @Test
    void aNonPositiveTimeoutIsRejectedBeforeDispatch(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                while IFS= read -r request; do
                    printf '%%begin 101 1 0\n%%end 101 1 0\n'
                done
                """);

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"))) {
            assertThrows(IllegalArgumentException.class, () -> client.send(List.of("list-windows"), Duration.ZERO));
            assertTrue(client.send("list-panes").succeeded(), "rejection wrote nothing to the stream");
        }
    }

    @Test
    void aRejectedNulDoesNotStealTheNextRequestsReply(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                while IFS= read -r request; do
                    printf '%%begin 101 1 0\nstill in step\n%%end 101 1 0\n'
                done
                """);

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.send(List.of("display-message", "contains\0nul"), Duration.ofMillis(200)));

            assertEquals(
                    List.of("still in step"),
                    client.send(List.of("display-message", "valid"), Duration.ofMillis(200))
                            .lines());
        }
    }

    @Test
    void interruptionIsNotReportedAsADeadlineExpiry(@TempDir Path directory) throws Exception {
        Path dispatched = directory.resolve("dispatched");
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                IFS= read -r request
                : > "${0%/*}/dispatched"
                IFS= read -r never
                """);

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"))) {
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<InterruptedFailure> waiting = new FutureTask<>(() -> {
                entered.countDown();
                try {
                    client.send(List.of("list-windows"), Duration.ofSeconds(30));
                    throw new AssertionError("the interrupted request unexpectedly completed");
                } catch (TmuxTransportException failure) {
                    return new InterruptedFailure(
                            failure, Thread.currentThread().isInterrupted());
                }
            });
            Thread caller = Thread.ofVirtual().start(waiting);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(awaitFile(dispatched), "the writer never dispatched the request");

            caller.interrupt();

            InterruptedFailure result = waiting.get(5, TimeUnit.SECONDS);
            assertFalse(result.failure() instanceof TmuxTimeoutException);
            assertInstanceOf(TmuxTransportException.class, result.failure());
            assertEquals(DispatchOutcome.UNKNOWN, result.failure().outcome());
            assertTrue(result.interrupted(), "the caller's interrupt status was lost");
        }
    }

    @Test
    void anAttachErrorIsNotAcceptedAsAReadyClient(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\nattach refused\n%%error 100 1 0\n'
                """);

        assertThrows(LibTmuxException.class, () -> ControlClient.attach(config, new SessionId("$0")));
    }

    @Test
    void anAttachDeadlineKeepsItsTimeoutType(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, "sleep 5\n");

        TmuxTimeoutException timeout = assertThrows(
                TmuxTimeoutException.class,
                () -> ControlClient.attach(config, new SessionId("$0"), Duration.ofMillis(100)));

        assertEquals(DispatchOutcome.UNKNOWN, timeout.outcome());
    }

    @Test
    void stderrCannotBlockTheOpeningControlReply(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                i=0
                while [ "$i" -lt 4096 ]; do
                    printf 'control-stderr-flood-0123456789\\n' >&2
                    i=$((i + 1))
                done
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                while IFS= read -r request; do :; done
                """);

        try (ControlClient client = ControlClient.attach(config, new SessionId("$0"), Duration.ofSeconds(2))) {
            assertTrue(client.isAlive());
        }
    }

    @Test
    void closingAControlClientReclaimsDescendantsThatInheritedItsPipes(@TempDir Path directory) throws Exception {
        Path childFile = directory.resolve("child-pid");
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                sleep 30 &
                printf '%s\n' "$!" > "${0%/*}/child-pid"
                while IFS= read -r request; do :; done
                """);
        long child = -1;
        try {
            ControlClient client = ControlClient.attach(config, new SessionId("$0"));
            assertTrue(awaitFile(childFile), "the fake control client never started its descendant");
            child = Long.parseLong(Files.readString(childFile).trim());

            client.close();

            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if (child > 0) {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void aWriterFailureReclaimsDescendantsBeforeKillingTheControlProcess(@TempDir Path directory) throws Exception {
        Path childFile = directory.resolve("child-pid");
        Path ready = directory.resolve("stdin-closed");
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                sh -c 'trap "" HUP TERM; exec sleep 30' </dev/null >/dev/null 2>&1 &
                printf '%s\n' "$!" > "${0%/*}/child-pid"
                exec 0<&-
                : > "${0%/*}/stdin-closed"
                while :; do sleep 30; done
                """);
        long child = -1;
        try {
            try (ControlClient client = ControlClient.attach(config, new SessionId("$0"))) {
                assertTrue(awaitFile(childFile), "the fake control client never started its descendant");
                assertTrue(awaitFile(ready), "the fake control client never closed its request pipe");
                child = Long.parseLong(Files.readString(childFile).trim());

                assertThrows(TmuxTransportException.class, () -> client.send("list-windows"));

                assertTrue(awaitDead(child), "the failed control client orphaned its descendant");
            }
        } finally {
            if (child > 0) {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static ServerConfig fakeTmux(Path directory, String body) throws Exception {
        Path fakeTmux = directory.resolve("tmux");
        Files.writeString(fakeTmux, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(fakeTmux, PosixFilePermissions.fromString("rwx------"));
        return ServerConfig.builder().binary(fakeTmux.toString()).build();
    }

    private static boolean awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return Files.exists(file);
    }

    private static boolean awaitDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private record InterruptedFailure(TmuxTransportException failure, boolean interrupted) {}
}
