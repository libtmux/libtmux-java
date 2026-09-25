package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertAll;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server against real tmux, on a private socket with an explicitly empty config.
 *
 * <p>Every case here owns its socket, so nothing it does can reach a tmux the developer is using.
 * The config file is pinned and empty for the same reason: a user's own {@code .tmux.conf} would
 * otherwise decide what these assertions see.
 */
final class ServerTest {

    @Test
    void optionReadsUseDaemonEncodingAndPreserveMetadataFailures(@TempDir Path directory) throws IOException {
        var version =
                new java.util.concurrent.atomic.AtomicReference<>(new CommandResult(0, List.of("3.4"), List.of()));
        var value = new java.util.concurrent.atomic.AtomicReference<>(
                new CommandResult(0, List.of("@value a\\rb"), List.of()));
        List<CommandRequest> requests = new CopyOnWriteArrayList<>();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                requests.add(request);
                return switch (request.commands().getFirst().getFirst()) {
                    case "display-message" -> version.get();
                    case "show-options" -> value.get();
                    default -> throw new AssertionError("option read selected another transport command");
                };
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(config(directory), transport)) {
            assertEquals(java.util.Optional.of("a\rb"), server.globalOptions().get("@value"));
            assertEquals(
                    List.of("show-options", "-g", "-A", "--", "@value"),
                    requests.getLast().commands().getFirst());
            value.set(new CommandResult(0, List.of("@value a\\377\\376é"), List.of()));
            assertEquals(
                    java.util.Optional.of("a\\xff\\xfeé"),
                    server.globalOptions().get("@value"));
            value.set(new CommandResult(1, List.of(), List.of("invalid option")));
            assertTrue(server.globalOptions().get("@missing").isEmpty());
            value.set(new CommandResult(0, List.of("@value unknown\\e"), List.of()));
            assertThrows(LibTmuxException.class, () -> server.globalOptions().get("@value"));
            version.set(new CommandResult(0, List.of("unknown"), List.of()));
            assertThrows(LibTmuxException.class, () -> server.globalOptions().get("@value"));
            version.set(new CommandResult(1, List.of(), List.of("server exited unexpectedly")));
            value.set(new CommandResult(1, List.of(), List.of("server exited unexpectedly")));
            assertThrows(
                    ServerNotRunningException.class,
                    () -> server.globalOptions().get("@value"));
            assertEquals(
                    List.of("show-options", "-g", "-A", "-v", "--", "@value"),
                    requests.getLast().commands().get(1));
        }
    }

    private static ServerConfig config(Path directory) throws IOException {
        Path config = directory.resolve("empty.conf");
        Files.writeString(config, "");
        return ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .configFile(config)
                .build();
    }

    /**
     * A binary that is not tmux: exits 0 and prints nothing, on every platform. {@code /bin/true}
     * answers this on Linux but not macOS, which keeps it only under {@code /usr/bin}.
     */
    private static Path notTmux(Path directory) throws IOException {
        Path binary = directory.resolve("not-tmux");
        Files.writeString(binary, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        return binary;
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
        Path quarantinePath = Path.of(quarantine);
        assertEquals(
                Path.of("/tmp/libtmux-java-test"),
                quarantinePath.getParent(),
                "the quarantine must stay inside this port's test root");
        assertTrue(
                quarantinePath.getFileName().toString().matches("[0-9a-f]{16}"),
                "the quarantine must identify this build without exposing its path: " + quarantine);
        assertTrue(quarantine.length() <= 40, "named sockets need a short quarantine: " + quarantine);
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

    /**
     * A deadline the caller chose binds that call, and the handle's own default binds the rest.
     *
     * <p>The overload exists for a caller that has to finish — a fixture proving its server is gone,
     * a shutdown path that cannot hang — so what is asserted is that the chosen bound actually
     * reaches tmux rather than being dropped for {@code defaultTimeout}.
     */
    @Test
    void aChosenDeadlineBindsOnlyTheCallThatChoseIt(@TempDir Path directory) throws IOException {
        Duration chosen = Duration.ofMillis(250);
        RecordingTransport transport = new RecordingTransport();

        try (Server server = Server.using(config(directory), transport)) {
            server.isAlive(chosen);
            server.killServer(chosen);
            server.isAlive();

            assertEquals(
                    List.of(chosen, chosen, server.config().defaultTimeout()),
                    transport.requests.stream().map(CommandRequest::timeout).toList());
        }
    }

    /**
     * Both commands {@code killServer} issues are bounded, not just the kill.
     *
     * <p>It kills and then looks again, because tmux reports a doomed request inconsistently. A
     * bound covering only the first would leave the confirming look on the handle default, which is
     * exactly the hang the overload was added to prevent. The refusing transport is what makes the
     * second command happen at all.
     */
    @Test
    void theConfirmingLookIsBoundedToo(@TempDir Path directory) throws IOException {
        Duration chosen = Duration.ofMillis(250);
        RecordingTransport refusing = new RecordingTransport(1);

        try (Server server = Server.using(config(directory), refusing)) {
            server.killServer(chosen);

            assertEquals(
                    List.of(chosen, chosen),
                    refusing.requests.stream().map(CommandRequest::timeout).toList(),
                    "the kill and the look that confirms it");
        }
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
        assertThrows(IllegalStateException.class, server::snapshot);
        assertThrows(IllegalStateException.class, server::sessions);
        assertThrows(IllegalStateException.class, server::windows);
        assertThrows(IllegalStateException.class, server::panes);
        assertThrows(IllegalStateException.class, server::clients);
        assertThrows(IllegalStateException.class, server::attachedSessions);
    }

    @Test
    void aWaitPropagatesTransportFailuresThatAreNotItsDeadline(@TempDir Path directory) throws IOException {
        TmuxTransportException failure = new TmuxTransportException("pipe failed", DispatchOutcome.UNKNOWN, null);
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.commands().get(0).contains("wait-for")) {
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
                            () -> server.channel("channel").await(java.time.Duration.ofSeconds(1))));
        }
    }

    @Test
    void aWaitWithSignalCapacityPreservesAPredispatchTimeout(@TempDir Path directory)
            throws IOException, InterruptedException {
        TmuxTimeoutException failure =
                new TmuxTimeoutException("waiting admission timed out", DispatchOutcome.NOT_DISPATCHED, null);
        java.util.concurrent.atomic.AtomicBoolean waiting = new java.util.concurrent.atomic.AtomicBoolean();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.commands().get(0).contains("wait-for")) {
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
            assertEquals(
                    WakeReason.SERVER_GONE, server.channel("self-signalled").await(java.time.Duration.ofSeconds(1)));
            assertFalse(waiting.get(), "an ordinary wait consumed reserved signal capacity");
            assertSame(
                    failure,
                    assertThrows(
                            TmuxTimeoutException.class,
                            () -> server.channel("channel").awaitReservingCapacity(java.time.Duration.ofSeconds(1))));
            assertTrue(waiting.get(), "wait-for used ordinary transport admission");
        }
    }

    @Test
    void liveReadsPreserveMalformedOrInconsistentCaptures(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        for (String sessionRow : List.of(
                String.join(separator, "$0", "alpha", "maybe", "0"),
                String.join(separator, "$0", "alpha", "1", "not-a-number"),
                String.join(separator, "$0", "alpha", "1", "1"))) {
            try (Server server = Server.using(config(directory), new SnapshotTransport(sessionRow))) {
                LibTmuxException failure = assertThrows(LibTmuxException.class, server::snapshot);

                assertTrue(failure.getCause() instanceof IllegalArgumentException, failure.toString());
                List<Executable> reads = liveReads(server);
                assertAll(java.util.stream.IntStream.range(0, reads.size()).mapToObj(i -> () -> {
                    LibTmuxException rejected =
                            assertThrows(LibTmuxException.class, reads.get(i), "live read " + i + ": " + sessionRow);
                    assertTrue(rejected.getCause() instanceof IllegalArgumentException, rejected.toString());
                }));
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
    void liveReadsRejectFailedIdentityProbes(@TempDir Path directory) throws IOException {
        try (Server server = Server.using(config(directory), new RefusingTransport("permission denied"))) {
            assertAll(liveReads(server).stream().map(read -> () -> {
                LibTmuxException failure = assertThrows(LibTmuxException.class, read);
                assertFalse(failure instanceof ServerNotRunningException, "not a missing daemon: " + failure);
            }));
        }
        for (String absent : List.of(
                "no server running on /tmp/libtmux-java-test/missing",
                "server exited unexpectedly",
                "error connecting to /tmp/libtmux-java-test/missing (No such file or directory)")) {
            try (Server server = Server.using(config(directory), new RefusingTransport(absent))) {
                assertAll(
                        absent,
                        liveReads(server).stream()
                                .map(read -> () -> assertThrows(ServerNotRunningException.class, read)));
            }
        }
    }

    @Test
    void liveReadsKeepTransportAndTimeoutDiagnostics(@TempDir Path directory) throws IOException {
        IOException cause = new IOException("reader failed");
        for (TmuxTransportException failure : List.of(
                new TmuxTransportException("capture pipe failed", DispatchOutcome.UNKNOWN, cause),
                new TmuxTimeoutException("admission timed out", DispatchOutcome.NOT_DISPATCHED, cause))) {
            TmuxTransport transport = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    throw failure;
                }

                @Override
                public void close() {}
            };
            try (Server server = Server.using(config(directory), transport)) {
                assertAll(liveReads(server).stream()
                        .map(read -> () -> assertSame(failure, assertThrows(TmuxTransportException.class, read))));
            }
        }
    }

    @Test
    void liveReadsRejectAMissingOrNonExecutableBinary(@TempDir Path directory) throws IOException {
        Path binary = directory.resolve("not-executable");
        Files.writeString(binary, "#!/bin/sh\nexit 0\n");
        for (Path unavailable : List.of(directory.resolve("missing-binary"), binary)) {
            try (Server server = Server.open(
                    config(directory).toBuilder().binary(unavailable.toString()).build())) {
                assertAll(liveReads(server).stream().map(read -> () -> {
                    TmuxTransportException failure = assertThrows(TmuxTransportException.class, read);
                    assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
                    assertTrue(failure.getCause() instanceof IOException);
                }));
            }
        }
    }

    @Test
    void liveReadsRejectAnAbsentDaemon(@TempDir Path directory) throws IOException {
        try (Server server = Server.open(config(directory))) {
            assertAll(
                    liveReads(server).stream().map(read -> () -> assertThrows(ServerNotRunningException.class, read)));
        }
    }

    @Test
    void aLiveServerCanSuccessfullyReportNoSessions(@TempDir Path directory) throws IOException {
        try (Server server = Server.open(config(directory))) {
            try {
                server.run(List.of("new-session", "-d", "-s", "temporary"));
                server.run(List.of("set-option", "-s", "exit-empty", "off"));
                server.run(List.of("kill-session", "-t", "=temporary"));

                assertTrue(server.isAlive());
                assertTrue(server.snapshot().serverPid().isPresent());
                assertEquals(List.of(), server.sessions());
                assertEquals(List.of(), server.clients());
                assertTrue(server.session("temporary").isEmpty());
            } finally {
                server.killServer();
            }
        }
    }

    @Test
    void bufferListingsPreserveFailures(@TempDir Path directory) throws IOException {
        for (String refusal : List.of("permission denied", "no server running", "list-buffers failed")) {
            try (Server server = Server.using(config(directory), new RefusingTransport(refusal))) {
                LibTmuxException failure = assertThrows(
                        LibTmuxException.class, () -> server.buffers().list());
                assertTrue(String.valueOf(failure.getMessage()).contains(refusal));
            }
        }
        try (Server server = Server.open(config(directory))) {
            assertThrows(LibTmuxException.class, () -> server.buffers().list());
        }
    }

    /** {@code show} must not fold an absent daemon into "no buffer named that", as it once did. */
    @Test
    void showingABufferDistinguishesAnAbsentDaemonFromAMissingName(@TempDir Path directory) throws IOException {
        try (Server server = Server.using(config(directory), new RefusingTransport("no buffer never-set"))) {
            assertThrows(
                    ObjectDoesNotExistException.class, () -> server.buffers().show("never-set"));
        }
        try (Server server = Server.using(config(directory), new RefusingTransport("no server running"))) {
            assertThrows(ServerNotRunningException.class, () -> server.buffers().show("never-set"));
        }
    }

    private static List<Executable> liveReads(Server server) {
        return List.of(
                server::snapshot,
                server::sessions,
                server::windows,
                server::panes,
                server::clients,
                server::attachedSessions,
                () -> server.session("missing"),
                () -> server.session(new SessionId("$99")),
                () -> server.pane(new PaneId("%99")),
                () -> server.window(new io.github.libtmux.snapshot.WindowContext(
                        new SessionId("$99"), new WindowIndex(0), new WindowId("@99"))),
                () -> server.windows(new WindowId("@99")));
    }

    @Test
    void snapshotKeepsTheIdentityOfALiveServerWithNoSessions(@TempDir Path directory) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                requests.incrementAndGet();
                return GroupedTmux.execute(request, 4242L, "3.2a", argv -> switch (argv.getFirst()) {
                    case "display-message" ->
                        new CommandResult(
                                0,
                                List.of(String.join(RowFormat.of("field").separator(), "4242", "3.2a", "1790000000")),
                                List.of());
                    case "list-sessions" -> new CommandResult(0, List.of(), List.of());
                    // tmux has no current target to list children against, and says so.
                    default -> new CommandResult(1, List.of(), List.of("no current target"));
                });
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            var snapshot = server.snapshot();

            assertEquals(4242L, snapshot.serverPid().orElseThrow());
            assertEquals(TmuxVersion.parse("3.2a"), snapshot.serverVersion().orElseThrow());
            assertTrue(snapshot.sessions().isEmpty());
            assertTrue(snapshot.windows().isEmpty());
            assertTrue(snapshot.panes().isEmpty());
            assertTrue(snapshot.clients().isEmpty());
            assertEquals(2, requests.get(), "tmux refused the rest of the group, which cost no further request");
            assertEquals(List.of(), server.sessions());
            assertEquals(List.of(), server.windows());
            assertEquals(List.of(), server.panes());
            assertEquals(List.of(), server.clients());
            assertEquals(List.of(), server.attachedSessions());
            assertTrue(server.session("missing").isEmpty());
            assertTrue(server.session(new SessionId("$99")).isEmpty());
            assertTrue(server.pane(new PaneId("%99")).isEmpty());
            assertTrue(server.window(new io.github.libtmux.snapshot.WindowContext(
                            new SessionId("$99"), new WindowIndex(0), new WindowId("@99")))
                    .isEmpty());
            assertEquals(List.of(), server.windows(new WindowId("@99")));
        }
    }

    /**
     * A pid is reusable, so the fence carries the version too: a different tmux that landed on the
     * pid just probed would otherwise answer as the server the rows are read from.
     */
    @Test
    void snapshotRefusesAServerThatReusedThePid(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return GroupedTmux.execute(request, 4242L, "3.6", GroupedTmux.STARTED + 5, argv -> switch (argv.get(
                        0)) {
                    // Probed as one server; the one answering the listings started later, on that pid
                    // and that version, as a restarted tmux in a container often is.
                    case "display-message" ->
                        new CommandResult(
                                0,
                                List.of(String.join(separator, "4242", "3.6", Long.toString(GroupedTmux.STARTED))),
                                List.of());
                    default -> new CommandResult(0, List.of(), List.of());
                });
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            assertThrows(LibTmuxException.class, server::snapshot);
        }
    }

    /**
     * The defect this pins: a release candidate reports its version as {@code 3.8-rc}, the parsed
     * version printed as {@code 3.8}, and the fence compared the two — so every capture of a tmux
     * 3.8 release candidate looked like a server replaced mid-read, and failed. Found by the first
     * matrix lane ever to run one.
     */
    @Test
    void aReleaseCandidateIsTheServerItSaysItIs(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return GroupedTmux.execute(request, 4242L, "3.8-rc", argv -> switch (argv.get(0)) {
                    case "display-message" ->
                        new CommandResult(
                                0, List.of(String.join(separator, "4242", "3.8-rc", "1790000000")), List.of());
                    default -> new CommandResult(0, List.of(), List.of());
                });
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            assertEquals(List.of(), server.sessions(), "a 3.8-rc server with nothing in it reads as that");
            assertEquals("3.8-rc", server.version().toString());
        }
    }

    /** The fence refuses a replaced server before a listing runs, so there is no first capture. */
    @Test
    void snapshotRetriesAChangedIncarnationAndKeepsOnlyTheSecondCapture(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        try (Server server = Server.using(
                config(directory),
                new SnapshotRaceTransport(
                        List.of("4242", "4343", "4343", "4343"),
                        List.of(String.join(separator, "$1", "new", "0", "0"))))) {
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

    /**
     * A pane with no process reports {@code pane_pid} as {@code 0} on every released tmux through
     * 3.7c; the built development tmux this port has no CI lane for reports it as an empty string
     * instead — confirmed directly against that binary with {@code split-window -E} and a pane
     * whose command has already exited, neither of which this test needs live tmux to prove. A
     * capture used to crash on the empty count before ever reaching a caller's own dead-pane check.
     */
    @Test
    void snapshotTreatsAnEmptyPanePidAsNoProcess(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return GroupedTmux.execute(request, 4242L, "3.6", argv -> switch (argv.get(0)) {
                    case "display-message" ->
                        new CommandResult(0, List.of(String.join(separator, "4242", "3.6", "1790000000")), List.of());
                    case "list-sessions" ->
                        new CommandResult(0, List.of(String.join(separator, "$0", "only", "1", "1")), List.of());
                    case "list-windows" ->
                        new CommandResult(
                                0,
                                List.of(String.join(separator, "$0", "@0", "0", "only", "1", "1", "0", "80", "24", "")),
                                List.of());
                    case "list-panes" ->
                        new CommandResult(
                                0,
                                List.of(String.join(
                                        separator, "$0", "@0", "0", "%0", "0", "1", "zsh", "80", "24", "0", "0", "",
                                        "/tmp", "", "0", "0", "0", "0")),
                                List.of());
                    default -> new CommandResult(0, List.of(), List.of());
                });
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            List<Pane> panes = server.panes();

            assertEquals(1, panes.size());
            assertTrue(
                    panes.get(0).pid().isEmpty(), "an empty pane_pid must read as no process, not crash the capture");
        }
    }

    /** A command fake has no control carrier. Attach must not start the local tmux instead. */
    @Test
    void controlRefusesATransportThatStartsNoClient(@TempDir Path directory) throws IOException {
        String separator = RowFormat.of("field").separator();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                List<String> argv = request.commands().get(0);
                if (argv.stream().anyMatch(word -> word.contains("#{pid} #{version}"))) {
                    return new CommandResult(0, List.of("4242 3.6"), List.of());
                }
                return GroupedTmux.execute(request, 4242L, "3.6", command -> switch (command.get(0)) {
                    case "display-message" ->
                        new CommandResult(0, List.of(String.join(separator, "4242", "3.6", "1790000000")), List.of());
                    case "list-sessions" ->
                        new CommandResult(0, List.of(String.join(separator, "$0", "only", "1", "1")), List.of());
                    case "list-windows" ->
                        new CommandResult(
                                0,
                                List.of(String.join(separator, "$0", "@0", "0", "only", "1", "1", "0", "80", "24", "")),
                                List.of());
                    case "list-panes" ->
                        new CommandResult(
                                0,
                                List.of(String.join(
                                        separator, "$0", "@0", "0", "%0", "0", "1", "zsh", "80", "24", "0", "0", "",
                                        "/tmp", "", "0", "0", "0", "0")),
                                List.of());
                    default -> new CommandResult(0, List.of(), List.of());
                });
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            Session session = server.sessions().get(0);
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> server.control(session));

            assertTrue(
                    String.valueOf(refused.getMessage()).contains("does not start control clients"),
                    refused.getMessage());
        }
    }

    // --------------------------------------------------------------------------- version gates

    /**
     * The guard asks the daemon rather than trusting a version string, so it cannot fail the way a
     * version predicate can: a floor fitted to the two adjacent releases a matrix happens to run,
     * one lettered patch off from what tmux's own history says. Proven by making the two disagree -
     * a version the constant would refuse, with {@code list-commands} naming the command anyway, and
     * the reverse - and checking the daemon's answer is the one that wins.
     */
    @Test
    void promptHistoryAsksTheDaemonRatherThanTrustingAVersionString(@TempDir Path directory) throws IOException {
        try (Server server =
                Server.using(config(directory), listingCommands("3.2a", "show-prompt-history (showphist) [-T type]"))) {
            assertDoesNotThrow(
                    () -> server.prompt().history(), "list-commands names it, so a low version must not refuse");
            assertDoesNotThrow(() -> server.prompt().clear());
        }
        try (Server server = Server.using(config(directory), listingCommands("99.0"))) {
            UnsupportedTmuxVersionException refused = assertThrows(
                    UnsupportedTmuxVersionException.class, () -> server.prompt().history());

            assertTrue(
                    String.valueOf(refused.getMessage()).contains("3.3"),
                    "the refusal still names the release most callers will recognise: " + refused.getMessage());
            assertThrows(
                    UnsupportedTmuxVersionException.class, () -> server.prompt().clear());
        }
    }

    /**
     * An older client cannot talk to a newer daemon at all - confirmed against the matrix,
     * a 3.2a client against a 3.7c daemon on the same socket fails outright, {@code server exited
     * unexpectedly}. tmux reports that identically to "no daemon ever started here", so {@code
     * versionForCreation} used to read it as "no daemon" and quietly substitute the configured
     * binary's own {@code -V}, inverting {@link Server#version()}'s documented guarantee that it
     * reports the running server's version rather than the binary's.
     */
    @Test
    void versionForCreationDoesNotSubstituteTheBinarysVersionForADaemonItCannotReach(@TempDir Path directory)
            throws IOException {
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                List<String> argv = request.commands().get(0);
                if (argv.get(0).equals("-V")) {
                    // The binary answers -V without ever touching the socket, whatever is on it.
                    return new CommandResult(0, List.of("tmux 3.2a"), List.of());
                }
                return new CommandResult(1, List.of(), List.of("server exited unexpectedly"));
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(config(directory), transport)) {
            LibTmuxException failure = assertThrows(
                    LibTmuxException.class, () -> server.newSession(s -> s.sized(new Dimensions(120, 40))));

            assertFalse(
                    failure instanceof UnsupportedTmuxVersionException,
                    "must not claim a version for a daemon it never actually asked: " + failure);
            assertTrue(
                    String.valueOf(failure.getMessage()).contains("server exited unexpectedly"),
                    "tmux's own words, not a guess: " + failure.getMessage());
        }
    }

    /** Exit status and error lines are fields, not words to scrape out of the message. */
    @Test
    void aFailedCommandKeepsItsExitAndErrorLines(@TempDir Path directory) throws IOException {
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(config(directory), transport)) {
            LibTmuxException failure =
                    server.failed("display-message", new CommandResult(7, List.of(), List.of("no current target")));

            assertEquals(Optional.of("display-message"), failure.command());
            assertEquals(OptionalInt.of(7), failure.exitCode());
            assertEquals(List.of("no current target"), failure.errorLines());
            assertTrue(String.valueOf(failure.getMessage()).contains("exit 7"), failure.getMessage());

            LibTmuxException absent =
                    server.failed("kill-server", new CommandResult(1, List.of(), List.of("no server running")));

            assertTrue(absent instanceof ServerNotRunningException);
            assertEquals(Optional.of("kill-server"), absent.command());
            assertEquals(OptionalInt.of(1), absent.exitCode());
            assertEquals(List.of("no server running"), absent.errorLines());
        }
    }

    // ------------------------------------------------------------------------- session creation

    /**
     * {@code /bin/true} exits 0 and prints nothing, which {@code run} cannot tell apart
     * from tmux genuinely succeeding with an empty answer. Before this, an empty {@code stdout} list
     * reached {@code SessionId(reported.get(0))} directly and threw an unchecked collection
     * exception with neither tmux's stderr nor the configured binary in it.
     */
    @Test
    void aBinaryThatIsNotTmuxFailsSessionCreationWithATypedMessage(@TempDir Path directory) throws IOException {
        Path binary = notTmux(directory);
        ServerConfig notTmuxConfig = ServerConfig.builder()
                .binary(binary.toString())
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .build();

        try (Server server = Server.open(notTmuxConfig)) {
            // assertThrows itself is the regression guard: an ArrayIndexOutOfBoundsException
            // would not satisfy LibTmuxException.class and would fail this call as an unexpected type.
            LibTmuxException failure = assertThrows(LibTmuxException.class, () -> server.newSession("x"));

            assertTrue(
                    String.valueOf(failure.getMessage()).contains(binary.toString()),
                    "names the binary a caller misconfigured: " + failure.getMessage());
        }
    }

    /**
     * The same misconfiguration, but with a spec that forces a version probe first
     * ({@code versionForCreation} -&gt; {@code version()} -&gt; the server identity read). Before
     * this, {@code /bin/true}'s empty answer produced "tmux did not report exactly one server
     * identity row" - a message that reads like a transient parsing problem with a real tmux, not
     * "the configured binary is not tmux at all".
     */
    @Test
    void aBinaryThatIsNotTmuxNamesItselfEvenWhenAVersionProbeRunsFirst(@TempDir Path directory) throws IOException {
        Path binary = notTmux(directory);
        ServerConfig notTmuxConfig = ServerConfig.builder()
                .binary(binary.toString())
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .build();

        try (Server server = Server.open(notTmuxConfig)) {
            LibTmuxException failure = assertThrows(
                    LibTmuxException.class, () -> server.newSession(s -> s.sized(new Dimensions(120, 40))));

            assertTrue(
                    String.valueOf(failure.getMessage()).contains(binary.toString()),
                    "names the binary rather than describing a malformed row: " + failure.getMessage());
        }
    }

    /**
     * An explicit socket under a directory that does not exist is a path the
     * operator chose, so it is reported rather than created. tmux prints {@code error creating ...}
     * and exits 0 - the same empty-output shape as {@code /bin/true} above, reached through ordinary
     * misconfiguration rather than a non-tmux binary.
     */
    @Test
    void aSocketUnderAMissingDirectoryFailsSessionCreationWithTmuxsOwnReason(@TempDir Path directory)
            throws IOException {
        ServerConfig missingDirectory = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(directory.resolve("missing").resolve("s")))
                .build();

        try (Server server = Server.open(missingDirectory)) {
            LibTmuxException failure = assertThrows(LibTmuxException.class, () -> server.newSession("x"));

            assertTrue(
                    String.valueOf(failure.getMessage()).contains("error creating"),
                    "tmux's own reason, not a generic message: " + failure.getMessage());
        }
    }

    /**
     * A transport that answers the identity probe with {@code version} and {@code list-commands}
     * with {@code lines}, whatever the real relationship between the two would be.
     */
    private static TmuxTransport listingCommands(String version, String... lines) {
        String separator = RowFormat.of("field").separator();
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                List<String> argv = request.commands().get(0);
                if (argv.get(0).equals("display-message")) {
                    return new CommandResult(
                            0, List.of(String.join(separator, "4242", version, "1790000000")), List.of());
                }
                if (argv.get(0).equals("list-commands")) {
                    return new CommandResult(0, List.of(lines), List.of());
                }
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
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
                assertDoesNotThrow(() -> server.killServer(), "tmux said: " + refusal);
            }
        }
    }

    /** Another transport's pid is not a process on this host, however much it looks like tmux. */
    @Test
    void killingThroughAnotherTransportWaitsOnNoLocalProcess(@TempDir Path directory) throws IOException {
        Path lookalike = directory.resolve("tmux-lookalike");
        Files.copy(Path.of("/bin/sleep"), lookalike);
        assertTrue(lookalike.toFile().setExecutable(true));
        Process sleeper = new ProcessBuilder(lookalike.toString(), "30").start();
        try {
            TmuxTransport transport = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    return new CommandResult(0, List.of(Long.toString(sleeper.pid())), List.of());
                }

                @Override
                public void close() {}
            };
            try (Server server = Server.using(config(directory), transport)) {
                server.killServer(Duration.ofMillis(300));
            }
            assertTrue(sleeper.isAlive());
        } finally {
            sleeper.destroyForcibly();
        }
    }

    @Test
    void aServerThatSurvivesTheKillIsReportedRatherThanIgnored(@TempDir Path directory) throws IOException {
        try (Server server = Server.using(config(directory), new SurvivingTransport())) {
            LibTmuxException raised = assertThrows(LibTmuxException.class, () -> server.killServer());

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
            return request.commands().stream().anyMatch(command -> command.contains("kill-server"))
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
        private final List<CommandRequest> requests = new CopyOnWriteArrayList<>();
        private final int exitCode;

        RecordingTransport() {
            this(0);
        }

        /** @param exitCode what every command answers with, so a caller can choose the branch taken */
        RecordingTransport(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            executions.incrementAndGet();
            requests.add(request);
            return new CommandResult(exitCode, List.of("ok"), List.of());
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private record SnapshotTransport(String sessionRow) implements TmuxTransport {

        @Override
        public CommandResult execute(CommandRequest request) {
            return GroupedTmux.execute(request, 4242L, argv -> switch (argv.get(0)) {
                case "list-sessions" -> new CommandResult(0, List.of(sessionRow), List.of());
                case "display-message" ->
                    new CommandResult(
                            0,
                            List.of(String.join(RowFormat.of("field").separator(), "4242", "3.6", "1790000000")),
                            List.of());
                default -> new CommandResult(0, List.of(), List.of());
            });
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

        /**
         * Identities come in pairs: what a capture's probe is told, then what the server has become
         * by the time its listings run. A capture is two requests, so the pair is the whole race.
         */
        @Override
        public CommandResult execute(CommandRequest request) {
            if (request.commands().get(0).get(0).equals("display-message")) {
                return identity(at(2 * identityReads.getAndIncrement()));
            }
            String live = at(2 * (identityReads.get() - 1) + 1);
            if (live.isEmpty()) {
                return new CommandResult(1, List.of(), List.of("no server running on /tmp/s"));
            }
            return GroupedTmux.execute(request, Long.parseLong(live), argv -> switch (argv.get(0)) {
                case "list-sessions" ->
                    new CommandResult(0, List.of(sessionRows.get(sessionReads.getAndIncrement())), List.of());
                default -> new CommandResult(0, List.of(), List.of());
            });
        }

        /** Clamped, so a server that has gone stays gone however often it is asked about. */
        private String at(int index) {
            return identities.get(Math.min(index, identities.size() - 1));
        }

        private static CommandResult identity(String pid) {
            if (pid.isEmpty()) {
                return new CommandResult(1, List.of(), List.of("no server running on /tmp/s"));
            }
            return new CommandResult(
                    0, List.of(String.join(RowFormat.of("field").separator(), pid, "3.6", "1790000000")), List.of());
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

        /**
         * The server is still the one the probe named while its listings run, so the fence passes
         * and the capture fails for the reason under test rather than for the replacement.
         */
        @Override
        public CommandResult execute(CommandRequest request) {
            boolean firstCapture = identityReads.get() == 1;
            return GroupedTmux.execute(request, firstCapture ? 4242L : 4343L, argv -> switch (argv.get(0)) {
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
            });
        }

        private static CommandResult identity(String pid, String version) {
            return new CommandResult(0, List.of(row(pid, version, Long.toString(GroupedTmux.STARTED))), List.of());
        }

        private static String row(String... fields) {
            return String.join(RowFormat.of("field").separator(), fields);
        }

        @Override
        public void close() {}
    }
}
