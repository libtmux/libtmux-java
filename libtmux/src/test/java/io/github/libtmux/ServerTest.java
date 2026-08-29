package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransport;
import io.github.libtmux.transport.TmuxTransportException;
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

    @Test
    void aWaitPropagatesTransportFailuresThatAreNotItsDeadline(@TempDir Path directory) throws IOException {
        TmuxTransportException failure = new TmuxTransportException("pipe failed", DispatchOutcome.UNKNOWN, null);
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.argv().contains("wait-for")) {
                    throw failure;
                }
                return new CommandResult(0, List.of("4242"), List.of());
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            assertSame(
                    failure,
                    assertThrows(
                            TmuxTransportException.class,
                            () -> server.waitFor("channel", java.time.Duration.ofSeconds(1))));
        }
    }

    @Test
    void aWaitWithSignalCapacityPreservesAPredispatchTimeout(@TempDir Path directory) throws IOException {
        TmuxTimeoutException failure =
                new TmuxTimeoutException("waiting admission timed out", DispatchOutcome.NOT_DISPATCHED, null);
        java.util.concurrent.atomic.AtomicBoolean waiting = new java.util.concurrent.atomic.AtomicBoolean();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.argv().contains("wait-for")) {
                    throw failure;
                }
                return new CommandResult(1, List.of(), List.of("no server running"));
            }

            @Override
            public CommandResult executeWaiting(CommandRequest request) {
                waiting.set(true);
                throw failure;
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            assertEquals(WakeReason.SERVER_GONE, server.waitFor("self-signalled", java.time.Duration.ofSeconds(1)));
            assertFalse(waiting.get(), "an ordinary wait consumed reserved signal capacity");
            assertSame(
                    failure,
                    assertThrows(
                            TmuxTimeoutException.class,
                            () -> server.waitForWithSignalCapacity("channel", java.time.Duration.ofSeconds(1))));
            assertTrue(waiting.get(), "wait-for used ordinary transport admission");
        }
    }

    @Test
    void malformedOrInconsistentListingsRespectStrictAndLenientBoundaries(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        for (String sessionRow : List.of(
                String.join(separator, "$0", "alpha", "maybe", "0"),
                String.join(separator, "$0", "alpha", "1", "not-a-number"),
                String.join(separator, "$0", "alpha", "1", "1"))) {
            try (Server server = Server.using(config(directory), new SnapshotTransport(sessionRow))) {
                LibTmuxException failure = assertThrows(LibTmuxException.class, server::snapshot);

                assertTrue(failure.getCause() instanceof IllegalArgumentException, failure.toString());
                assertEquals(List.of(), server.sessions(), "lenient listings collapse hydration failures to empty");
            }
        }
    }

    @Test
    void moreThanOneAttachedClientStillMeansTheSessionIsAttached(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        String sessionRow = String.join(separator, "$0", "alpha", "2", "0");

        try (Server server = Server.using(config(directory), new SnapshotTransport(sessionRow))) {
            assertTrue(server.snapshot().sessions().get(0).attached());
        }
    }

    @Test
    void snapshotDistinguishesAnAbsentServerFromAnIdentityProbeFailure(@TempDir Path directory) throws IOException {
        try (Server server = Server.using(config(directory), new RefusingTransport("permission denied"))) {
            LibTmuxException failure = assertThrows(LibTmuxException.class, server::snapshot);

            assertTrue(String.valueOf(failure.getMessage()).contains("permission denied"));
        }
        for (String absent : List.of(
                "no server running on /tmp/s",
                "server exited unexpectedly",
                "error connecting to /tmp/s (No such file or directory)")) {
            try (Server server = Server.using(config(directory), new RefusingTransport(absent))) {
                assertTrue(server.snapshot().sessions().isEmpty(), absent);
            }
        }
    }

    @Test
    void snapshotRetriesAChangedIncarnationAndKeepsOnlyTheSecondCapture(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        try (Server server = Server.using(
                config(directory),
                new SnapshotRaceTransport(
                        List.of("4242", "4343", "4343", "4343"),
                        List.of(
                                String.join(separator, "$0", "old", "0", "0"),
                                String.join(separator, "$1", "new", "0", "0"))))) {
            var snapshot = server.snapshot();

            assertEquals(4343L, snapshot.serverPid().orElseThrow());
            assertEquals(
                    List.of("new"),
                    snapshot.sessions().stream().map(session -> session.name()).toList());
        }
    }

    @Test
    void snapshotRetriesWhenTheReplacedServerMakesAListingFail(@TempDir Path directory) throws IOException {
        try (Server server =
                Server.using(config(directory), new ReplacementDuringCaptureTransport(CaptureFailure.LISTING))) {
            var snapshot = server.snapshot();

            assertEquals(4343L, snapshot.serverPid().orElseThrow());
            assertEquals("new", snapshot.sessions().get(0).name());
        }
    }

    @Test
    void snapshotRetriesWhenTheReplacedServerChangesThePaneRowShape(@TempDir Path directory) throws IOException {
        try (Server server =
                Server.using(config(directory), new ReplacementDuringCaptureTransport(CaptureFailure.PANE_SHAPE))) {
            var snapshot = server.snapshot();

            assertEquals(4343L, snapshot.serverPid().orElseThrow());
            assertEquals("new", snapshot.sessions().get(0).name());
        }
    }

    @Test
    void snapshotRejectsASecondReplacementDuringHydration(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        try (Server server = Server.using(
                config(directory),
                new SnapshotRaceTransport(
                        List.of("4242", "4343", "4343", "4545"),
                        List.of(
                                String.join(separator, "$0", "old", "0", "0"),
                                String.join(separator, "$1", "new", "0", "0"))))) {
            LibTmuxException failure = assertThrows(LibTmuxException.class, server::snapshot);

            assertTrue(String.valueOf(failure.getMessage()).contains("changed during snapshot"));
        }
    }

    @Test
    void snapshotRejectsASecondDisappearanceDuringHydration(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        try (Server server = Server.using(
                config(directory),
                new SnapshotRaceTransport(
                        List.of("4242", "4343", "4343", ""),
                        List.of(
                                String.join(separator, "$0", "old", "0", "0"),
                                String.join(separator, "$1", "new", "0", "0"))))) {
            LibTmuxException failure = assertThrows(LibTmuxException.class, server::snapshot);

            assertTrue(String.valueOf(failure.getMessage()).contains("changed during snapshot"));
        }
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

    private record SnapshotTransport(String sessionRow) implements TmuxTransport {

        @Override
        public CommandResult execute(CommandRequest request) {
            return switch (request.argv().get(0)) {
                case "list-sessions" -> new CommandResult(0, List.of(sessionRow), List.of());
                case "display-message" ->
                    new CommandResult(
                            0, List.of(String.join(RowFormat.of("field").separator(), "4242", "3.6")), List.of());
                default -> new CommandResult(0, List.of(), List.of());
            };
        }

        @Override
        public void close() {}
    }

    private static final class SnapshotRaceTransport implements TmuxTransport {

        private final AtomicInteger identityReads = new AtomicInteger();
        private final AtomicInteger sessionReads = new AtomicInteger();
        private final List<String> identities;
        private final List<String> sessionRows;

        SnapshotRaceTransport(List<String> identities, List<String> sessionRows) {
            this.identities = identities;
            this.sessionRows = sessionRows;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            return switch (request.argv().get(0)) {
                case "display-message" -> identity(identities.get(identityReads.getAndIncrement()));
                case "list-sessions" ->
                    new CommandResult(0, List.of(sessionRows.get(sessionReads.getAndIncrement())), List.of());
                default -> new CommandResult(0, List.of(), List.of());
            };
        }

        private static CommandResult identity(String pid) {
            if (pid.isEmpty()) {
                return new CommandResult(1, List.of(), List.of("no server running on /tmp/s"));
            }
            return new CommandResult(
                    0, List.of(String.join(RowFormat.of("field").separator(), pid, "3.6")), List.of());
        }

        @Override
        public void close() {}
    }

    private enum CaptureFailure {
        LISTING,
        PANE_SHAPE
    }

    private static final class ReplacementDuringCaptureTransport implements TmuxTransport {

        private final AtomicInteger identityReads = new AtomicInteger();
        private final CaptureFailure failure;

        ReplacementDuringCaptureTransport(CaptureFailure failure) {
            this.failure = failure;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            boolean firstCapture = identityReads.get() == 1;
            return switch (request.argv().get(0)) {
                case "display-message" ->
                    identityReads.getAndIncrement() == 0
                            ? identity("4242", failure == CaptureFailure.PANE_SHAPE ? "3.7" : "3.6")
                            : identity("4343", "3.6");
                case "list-sessions" -> {
                    if (firstCapture && failure == CaptureFailure.LISTING) {
                        yield new CommandResult(1, List.of(), List.of("server exited unexpectedly"));
                    }
                    yield new CommandResult(
                            0,
                            List.of(firstCapture ? row("$0", "old", "0", "1") : row("$1", "new", "0", "0")),
                            List.of());
                }
                case "list-windows" ->
                    new CommandResult(
                            0,
                            firstCapture && failure == CaptureFailure.PANE_SHAPE
                                    ? List.of(row("$0", "@0", "0", "old", "1", "1", "0", "80", "24", "layout"))
                                    : List.of(),
                            List.of());
                case "list-panes" ->
                    new CommandResult(
                            0,
                            firstCapture && failure == CaptureFailure.PANE_SHAPE
                                    ? List.of(row(
                                            "$0", "@0", "0", "%0", "0", "1", "sh", "80", "24", "", "/tmp", "7", "1",
                                            "1", "1", "1"))
                                    : List.of(),
                            List.of());
                default -> new CommandResult(0, List.of(), List.of());
            };
        }

        private static CommandResult identity(String pid, String version) {
            return new CommandResult(0, List.of(row(pid, version)), List.of());
        }

        private static String row(String... fields) {
            return String.join(RowFormat.of("field").separator(), fields);
        }

        @Override
        public void close() {}
    }
}
