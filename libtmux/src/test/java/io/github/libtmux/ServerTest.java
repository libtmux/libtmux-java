package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server against real tmux, on a private socket with an explicitly empty config.
 *
 * <p>Every case here owns its socket, so nothing it does can reach a tmux the developer is using.
 * The config file is pinned and empty for the same reason: a user's own {@code .tmux.conf} would
 * otherwise decide what these assertions see.
 */
final class ServerTest {

    private static ServerConfig config(Path directory) throws IOException {
        Path config = directory.resolve("empty.conf");
        Files.writeString(config, "");
        return ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .configFile(config)
                .build();
    }

    /**
     * The suite is quarantined from the developer's own tmux by the build, not by every test
     * remembering to pass {@code -S}. A command that omits the flag addresses a real server and can
     * kill it, so the quarantine is asserted rather than assumed.
     */
    @Test
    void nothingHereCanReachATmuxTheBuildDoesNotOwn() {
        assertNull(System.getenv("TMUX"), "a client started inside a pane ignores TMUX_TMPDIR");
        String quarantine = System.getenv("TMUX_TMPDIR");

        assertNotNull(quarantine, "without this a bare client lands on the developer's default socket");
        assertTrue(quarantine.contains("build"), "the quarantine must sit inside the build tree: " + quarantine);
    }

    // -------------------------------------------------------------------------------- dispatch

    @Test
    void aCommandRunsAgainstTheConfiguredServer(@TempDir Path directory) throws IOException {
        try (Server server = Server.open(config(directory))) {
            server.cmd("new-session", "-d", "-s", "alpha");

            CommandResult sessions = server.cmd("list-sessions", "-F", "#{session_name}");

            assertTrue(sessions.succeeded());
            assertEquals(List.of("alpha"), sessions.stdout());
            server.cmd("kill-server");
        }
    }

    @Test
    void aTmuxErrorIsReturnedAsDataRatherThanThrown(@TempDir Path directory) throws IOException {
        try (Server server = Server.open(config(directory))) {
            server.cmd("new-session", "-d", "-s", "alpha");

            CommandResult result = server.cmd("kill-session", "-t", "nope");

            assertFalse(result.succeeded(), "tmux reports an ordinary miss the same way it reports a problem");
            assertFalse(result.stderr().isEmpty());
            server.cmd("kill-server");
        }
    }

    @Test
    void twoServersOnOneSocketSeeTheSameTmux(@TempDir Path directory) throws IOException {
        ServerConfig config = config(directory);
        try (Server first = Server.open(config)) {
            first.cmd("new-session", "-d", "-s", "alpha");

            try (Server second = Server.open(config)) {
                assertEquals(
                        List.of("alpha"),
                        second.cmd("list-sessions", "-F", "#{session_name}").stdout());
            }
            first.cmd("kill-server");
        }
    }

    // ------------------------------------------------------------------------------- ownership

    /** Closing a client is not a reason to end everyone else's tmux session. */
    @Test
    void closingAServerNeverKillsTmux(@TempDir Path directory) throws IOException {
        ServerConfig config = config(directory);
        try (Server first = Server.open(config)) {
            first.cmd("new-session", "-d", "-s", "alpha");
        }

        try (Server second = Server.open(config)) {
            assertEquals(
                    List.of("alpha"),
                    second.cmd("list-sessions", "-F", "#{session_name}").stdout(),
                    "the session outlived the client that made it");
            second.cmd("kill-server");
        }
    }

    @Test
    void aBorrowedTransportOutlivesTheServerThatUsedIt(@TempDir Path directory) throws IOException {
        RecordingTransport transport = new RecordingTransport();

        try (Server server = Server.using(config(directory), transport)) {
            server.cmd("display-message", "-p", "ok");
        }

        assertEquals(0, transport.closes.get(), "a transport the caller owns is the caller's to close");
        assertEquals(1, transport.executions.get());
    }

    @Test
    void anOwnedTransportReleasesItsWorkers(@TempDir Path directory) throws IOException, InterruptedException {
        int before = pumpWorkers();

        try (Server server = Server.open(config(directory))) {
            server.cmd("new-session", "-d", "-s", "alpha");
            assertTrue(pumpWorkers() > before, "an owned transport really did start workers");
            server.cmd("kill-server");
        }

        assertTrue(awaitPumpWorkers(before), "an owned transport must release its workers on close");
    }

    @Test
    void closeIsIdempotent(@TempDir Path directory) throws IOException {
        Server server = Server.open(config(directory));

        server.close();
        server.close();
    }

    @Test
    void operationsAfterCloseAreRejected(@TempDir Path directory) throws IOException {
        Server server = Server.open(config(directory));
        server.close();

        assertThrows(IllegalStateException.class, () -> server.cmd("list-sessions"));
    }

    // -------------------------------------------------------------------------------- builders

    @Test
    void aServerCanBeBuiltWithoutSayingAnything() {
        try (Server server = Server.builder().build()) {
            assertEquals(ServerEndpoint.defaultSocket(), server.config().endpoint());
        }
    }

    @Test
    void toBuilderCarriesTheBorrowedTransportForward(@TempDir Path directory) throws IOException {
        RecordingTransport transport = new RecordingTransport();

        try (Server derived = Server.using(config(directory), transport).toBuilder()
                .binary("/usr/local/bin/tmux")
                .build()) {
            derived.cmd("display-message", "-p", "ok");
        }

        assertEquals(0, transport.closes.get(), "ownership is a choice toBuilder must carry, not reset");
        assertEquals(1, transport.executions.get());
    }

    // ------------------------------------------------------------------- killing an absent server

    /**
     * tmux has two ways of saying a server is already gone, and which one arrives is a race.
     *
     * <p>{@code no server running} is the usual answer; a client that reaches a socket whose server
     * is still exiting gets {@code server exited unexpectedly} instead. Measured on the release
     * matrix, that happens on every release from 3.3a onwards — about one attempt in thirty, and one
     * in five on 3.7. Against a real tmux this is a rare flake, so it is pinned here where the
     * answer can be chosen.
     */
    @Test
    void killingAnAbsentServerIsNotAFailureWhicheverWayTmuxSaysItIsAbsent(@TempDir Path directory) throws IOException {
        for (String refusal : List.of("no server running on /tmp/s", "server exited unexpectedly")) {
            try (Server server = Server.using(config(directory), new RefusingTransport(refusal))) {
                assertDoesNotThrow(server::killServer, "tmux said: " + refusal);
            }
        }
    }

    @Test
    void aServerThatSurvivesTheKillIsReportedRatherThanIgnored(@TempDir Path directory) throws IOException {
        try (Server server = Server.using(config(directory), new SurvivingTransport())) {
            LibTmuxException raised = assertThrows(LibTmuxException.class, server::killServer);

            assertTrue(String.valueOf(raised.getMessage()).contains("could not kill the server"));
        }
    }

    // -------------------------------------------------------------------------------- fixtures

    private static int pumpWorkers() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("libtmux-pump"))
                .count();
    }

    private static boolean awaitPumpWorkers(int target) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (pumpWorkers() <= target) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** Refuses everything with one chosen message, the way a socket with no server behind it does. */
    private record RefusingTransport(String stderr) implements TmuxTransport {

        @Override
        public CommandResult execute(CommandRequest request) {
            return new CommandResult(1, List.of(), List.of(stderr));
        }

        @Override
        public void close() {}
    }

    /** Refuses the kill and then answers, which is a server that is still there. */
    private static final class SurvivingTransport implements TmuxTransport {

        @Override
        public CommandResult execute(CommandRequest request) {
            return request.argv().contains("kill-server")
                    ? new CommandResult(1, List.of(), List.of("permission denied"))
                    : new CommandResult(0, List.of("4242"), List.of());
        }

        @Override
        public void close() {}
    }

    /** Counts what a server did to it, which is the only way ownership is observable. */
    private static final class RecordingTransport implements TmuxTransport {

        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public CommandResult execute(CommandRequest request) {
            executions.incrementAndGet();
            return new CommandResult(0, List.of("ok"), List.of());
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
