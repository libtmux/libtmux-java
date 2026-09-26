package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.PaneId;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.SessionId;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.transport.DispatchOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where one tmux command ends and the next begins, and what a control-mode line makes of that.
 *
 * <p>Every expectation here was measured against tmux rather than derived from this code. The
 * measurements are in {@code docs/decisions/0009-command-groups-are-transport-agnostic.md}.
 */
final class ControlClientTest {

    /** A pipe write on the calling virtual thread pins the only carrier and starves the sentinel. */
    @Test
    @Tag("carrier")
    void aBlockedControlWriteDoesNotOccupyTheCallersCarrier(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                # the client's own on-attach refresh-client -f new-layouts
                read_request
                answer
                sleep 5
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"))) {
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

    /** Quoting carries a semicolon anywhere in an argument, so no escape reaches tmux. */
    @Test
    void everyArgumentReachesTmuxExactlyAsGiven() {
        assertEquals(
                "'display-message' '-p' 'trailing;'",
                ControlClient.line(List.of("display-message", "-p", "trailing;")));
        assertEquals(
                "'display-message' '-p' 'trailing\\;'",
                ControlClient.line(List.of("display-message", "-p", "trailing\\;")));
        assertEquals(
                "'display-message' '-p' 'semi;colon'",
                ControlClient.line(List.of("display-message", "-p", "semi;colon")));
        assertEquals(
                "'display-message' '-p' 'it'\\''s quoted'",
                ControlClient.line(List.of("display-message", "-p", "it's quoted")));
    }

    /**
     * tmux(1) documents {@code refresh-client -B name:what:format}'s {@code what} as empty,
     * {@code %N}, {@code %*}, {@code @N} or {@code @*} only. A session id ({@code $0}) or an
     * arbitrary word were never spellings the manual promises for "the attached session" - 3.2a and
     * 3.7c happen to accept them leniently, but on master the identical call is accepted and fires
     * nothing, silently. {@code watch} now sends the one spelling confirmed to work everywhere -
     * {@code ""} - for anything that does not itself name a pane or window.
     */
    @Test
    void watchNormalizesAnySessionScopeTargetToTheEmptyString(@TempDir Path directory) throws Exception {
        String expected = ControlClient.line(List.of("refresh-client", "-B", "javatest::#{session_name}"));
        int attempt = 0;
        for (String target : List.of("$0", "session", "anything-else", "")) {
            String requestLine = capturedWatchRequest(directory, attempt++, target);
            assertEquals(expected, requestLine, "target '" + target + "' must normalize to the empty string");
        }
    }

    /** Attaches a fresh fake control client, sends one {@code watch}, and returns the request line it made. */
    private static String capturedWatchRequest(Path directory, int attempt, String target) throws Exception {
        Path scratch = directory.resolve("watch-" + attempt);
        Files.createDirectory(scratch);
        Path captured = scratch.resolve("captured-watch-request");
        ServerConfig config = fakeTmux(scratch, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                read_request
                answer
                read_request
                printf '%s\\n' "$request" > '""" + captured + """
                '
                answer
                sleep 5
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"))) {
            client.watch("javatest", target, "#{session_name}");
            assertTrue(awaitFile(captured), "the watch request never reached the fake server");
            return Files.readString(captured).strip();
        }
    }

    @Test
    void aTimedOutReplyMakesTheStreamUnavailableForLaterRequests(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                # the client's own on-attach refresh-client -f new-layouts
                read_request
                answer
                IFS= read -r request
                sleep 1
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"));
                EventSubscription<ControlEvent> events = client.subscribeEvents(1)) {
            DispatchException.TimedOut failure = assertThrows(
                    DispatchException.TimedOut.class,
                    () -> client.send(List.of("list-windows"), Duration.ofMillis(100)));

            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
            assertFalse(client.isAlive(), "a missing reply leaves command attribution uncertain");
            assertTrue(events.isClosed(), "a subscriber cannot wait forever on an unusable client");
            assertThrows(IllegalStateException.class, () -> client.send("list-panes"));
        }
    }

    @Test
    void closingTheClientWakesAWaitingSubscriber(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                # the client's own on-attach refresh-client -f new-layouts
                read_request
                answer
                IFS= read -r never
                """);
        ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"));
        EventSubscription<PaneOutput> output = client.subscribeOutput(1);
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<Optional<Delivery<PaneOutput>>> waiting = new FutureTask<>(() -> {
            entered.countDown();
            return output.next();
        });
        Thread consumer = Thread.ofVirtual().start(waiting);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));

            client.close();

            assertEquals(Optional.empty(), waiting.get(1, TimeUnit.SECONDS));
        } finally {
            waiting.cancel(true);
            output.close();
            client.close();
            consumer.join();
        }
    }

    @Test
    void aNonPositiveTimeoutIsRejectedBeforeDispatch(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                while read_request; do
                    answer
                done
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"))) {
            assertThrows(IllegalArgumentException.class, () -> client.send(List.of("list-windows"), Duration.ZERO));
            assertTrue(client.send("list-panes").succeeded(), "rejection wrote nothing to the stream");
        }
    }

    @Test
    void aRejectedNulDoesNotStealTheNextRequestsReply(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                while read_request; do
                    answer 'still in step'
                done
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"))) {
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
                # the client's own on-attach refresh-client -f new-layouts
                read_request
                answer
                read_request
                : > "${0%/*}/dispatched"
                IFS= read -r never
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"))) {
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<InterruptedFailure> waiting = new FutureTask<>(() -> {
                entered.countDown();
                try {
                    client.send(List.of("list-windows"), Duration.ofSeconds(30));
                    throw new AssertionError("the interrupted request unexpectedly completed");
                } catch (DispatchException failure) {
                    return new InterruptedFailure(
                            failure, Thread.currentThread().isInterrupted());
                }
            });
            Thread caller = Thread.ofVirtual().start(waiting);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(awaitFile(dispatched), "the writer never dispatched the request");

            caller.interrupt();

            InterruptedFailure result = waiting.get(5, TimeUnit.SECONDS);
            assertFalse(result.failure() instanceof DispatchException.TimedOut);
            assertInstanceOf(DispatchException.class, result.failure());
            assertEquals(DispatchOutcome.UNKNOWN, result.failure().outcome());
            assertTrue(result.interrupted(), "the caller's interrupt status was lost");
        }
    }

    @Test
    void anAttachErrorIsNotAcceptedAsAReadyClient(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\nattach refused\n%%error 100 1 0\n'
                """);

        assertThrows(LibTmuxException.class, () -> ControlClient.attachUnfenced(config, new SessionId("$0")));
    }

    @Test
    void anAttachDeadlineKeepsItsTimeoutType(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, "sleep 5\n");

        DispatchException.TimedOut timeout = assertThrows(
                DispatchException.TimedOut.class,
                () -> ControlClient.attachUnfenced(config, new SessionId("$0"), Duration.ofMillis(100)));

        assertEquals(DispatchOutcome.UNKNOWN, timeout.outcome());
    }

    /** Close, a refused send, and a short timeout, repeated. Threads from one cycle must not remain. */
    @Test
    void repeatedTimeoutAndCloseDoNotLeaveClients(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                read_request
                answer
                IFS= read -r request
                sleep 2
                """);
        int before = controlThreads();
        for (int cycle = 0; cycle < 12; cycle++) {
            ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"));
            DispatchException.TimedOut timeout = assertThrows(
                    DispatchException.TimedOut.class,
                    () -> client.send(List.of("list-windows"), Duration.ofMillis(80)));
            assertEquals(DispatchOutcome.UNKNOWN, timeout.outcome());
            client.close();
            assertFalse(client.isAlive());
            assertThrows(IllegalStateException.class, () -> client.send("list-panes"));
            client.close();
        }
        assertTrue(controlThreadsSettled(before), "control threads survived close");
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
                # includes the client's own on-attach refresh-client -f new-layouts
                while read_request; do answer; done
                """);

        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"), Duration.ofSeconds(2))) {
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
                # includes the client's own on-attach refresh-client -f new-layouts
                while read_request; do answer; done
                """);
        long child = -1;
        try {
            ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"));
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
                # the client's own on-attach refresh-client -f new-layouts
                read_request
                answer
                sh -c 'trap "" HUP TERM; exec sleep 30' </dev/null >/dev/null 2>&1 &
                printf '%s\n' "$!" > "${0%/*}/child-pid"
                exec 0<&-
                : > "${0%/*}/stdin-closed"
                while :; do sleep 30; done
                """);
        long child = -1;
        try {
            try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"))) {
                assertTrue(awaitFile(childFile), "the fake control client never started its descendant");
                assertTrue(awaitFile(ready), "the fake control client never closed its request pipe");
                child = Long.parseLong(Files.readString(childFile).trim());

                assertThrows(DispatchException.class, () -> client.send("list-windows"));

                assertTrue(awaitDead(child), "the failed control client orphaned its descendant");
            }
        } finally {
            if (child > 0) {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /**
     * tmux cuts pane output into %output lines by byte count, so a UTF-8 character can start in one
     * and end in the next. It escapes only control bytes and the backslash, as octal.
     */
    @Test
    void aCharacterSplitAcrossOutputLinesArrivesWhole(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                read_request
                answer
                read_request
                printf '%%output %%1 caf\\303\n%%output %%1 \\251\\\\134x!\n'
                answer
                IFS= read -r never
                """);
        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"));
                EventSubscription<PaneOutput> output = client.subscribeOutput(8)) {
            client.send("display-message");

            PaneOutput first = Delivery.kept(output.next(Duration.ofSeconds(5)).orElseThrow());
            PaneOutput second = Delivery.kept(output.next(Duration.ofSeconds(5)).orElseThrow());

            assertEquals("caf", first.data());
            assertEquals("é\\x!", second.data());
            assertEquals(ByteBuffer.wrap(new byte[] {'c', 'a', 'f', (byte) 0xc3}), first.bytes());
            assertEquals(ByteBuffer.wrap(new byte[] {(byte) 0xa9, '\\', 'x', '!'}), second.bytes());
        }
    }

    /**
     * Once a client turns on flow control, tmux sends {@code %extended-output}: the pane, how long the
     * output waited, then the escaped bytes after {@code " : "}. A line with no separator carries no
     * output and is skipped.
     */
    @Test
    void extendedOutputIsOutput(@TempDir Path directory) throws Exception {
        ServerConfig config = fakeTmux(directory, """
                printf '%%begin 100 1 0\n%%end 100 1 0\n'
                read_request
                answer
                read_request
                printf '%%extended-output %%2 17\n%%extended-output %%2 17 : a : b\\\\012\n'
                answer
                IFS= read -r never
                """);
        try (ControlClient client = ControlClient.attachUnfenced(config, new SessionId("$0"));
                EventSubscription<PaneOutput> output = client.subscribeOutput(8)) {
            client.send("display-message");

            PaneOutput piece = Delivery.kept(output.next(Duration.ofSeconds(5)).orElseThrow());

            assertEquals(new PaneId("%2"), piece.pane());
            assertEquals("a : b\n", piece.data());
        }
    }

    /**
     * What every fake starts with. tmux follows each request line with a marker line, a {@code
     * display-message -p} of a token, and a reply ends with the marker's block: {@code read_request}
     * reads a request and its marker, and {@code answer} writes a reply block, flagged as this
     * client's command, with its arguments as lines, then the marker's block.
     */
    private static final String PRELUDE = """
            #!/bin/sh
            read_request() { IFS= read -r request && IFS= read -r marker; }
            answer() {
                printf '%%begin 1 1 1\\n'
                for line in "$@"; do printf '%s\\n' "$line"; done
                printf '%%end 1 1 1\\n'
                token=${marker##*"' '"}
                printf '%%begin 1 2 1\\n%s\\n%%end 1 2 1\\n' "${token%"'"}"
            }
            """;

    private static ServerConfig fakeTmux(Path directory, String body) throws Exception {
        Path fakeTmux = directory.resolve("tmux");
        Files.writeString(fakeTmux, PRELUDE + body);
        Files.setPosixFilePermissions(fakeTmux, PosixFilePermissions.fromString("rwx------"));
        return ServerConfig.builder().binary(fakeTmux.toString()).build();
    }

    private static int controlThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("libtmux-control") && thread.isAlive())
                .count();
    }

    private static boolean controlThreadsSettled(int before) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (controlThreads() > before && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return controlThreads() <= before;
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

    private record InterruptedFailure(DispatchException failure, boolean interrupted) {}
}
