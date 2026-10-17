package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.support.Versions;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.Idempotence;
import io.github.libtmux.transport.TmuxTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class MinimumTmuxTest {

    @ParameterizedTest
    @ValueSource(strings = {"3.2a", "3.3", "next-3.3", "3.3-rc"})
    void oldDaemonsRejectRawReadsWritesAndGroups(String version) {
        FakeTmux tmux = new FakeTmux(version);
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertThrows(UnsupportedFeatureException.class, () -> server.cmd("list-sessions"));
            assertThrows(
                    UnsupportedFeatureException.class, () -> server.cmd("set-option", "-g", "@sentinel", "changed"));
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> server.batch().add("kill-server").run());
            assertThrows(UnsupportedFeatureException.class, server::killServer);
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> server.globalOptions().get("@sentinel"));
            assertEquals(0, tmux.effects.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.3a", "3.3b", "3.4", "3.7a", "3.8", "next-3.9"})
    void supportedDaemonWinsOverAnOlderClient(String version) {
        FakeTmux tmux = new FakeTmux(version);
        tmux.binaryVersion = "3.2a";
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertTrue(server.cmd("set-option", "-g", "@sentinel", "changed").succeeded());
            assertEquals(1, tmux.effects.get());
            assertEquals(0, tmux.binaryProbes.get());
        }
    }

    @Test
    void freshSocketChecksTheBinaryBeforeStartingADaemon() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.absent = true;
        tmux.binaryVersion = "3.3";
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertThrows(UnsupportedFeatureException.class, () -> server.cmd("new-session", "-d"));
            assertEquals(0, tmux.effects.get());
            assertEquals(1, tmux.binaryProbes.get());
        }
    }

    @Test
    void aDaemonReplacementCannotPassTheVersionProbeThenReceiveAMutation() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.liveVersion = "3.2a";
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertThrows(TargetGoneException.class, () -> server.cmd("set-option", "-g", "@sentinel", "changed"));
            assertEquals(0, tmux.effects.get());
        }
    }

    @Test
    void aFailedHandshakeNeverFallsBackToTheClientVersion() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.probeFailure = new CommandResult(1, List.of(), List.of("server exited unexpectedly"));
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            CommandRejectedException failure =
                    assertThrows(CommandRejectedException.class, () -> server.cmd("new-session", "-d"));
            assertTrue(String.valueOf(failure.getMessage()).contains("server exited unexpectedly"));
            assertEquals(0, tmux.effects.get());
            assertEquals(0, tmux.binaryProbes.get());
        }
    }

    @Test
    void unknownDaemonVersionFailsBeforeEffects() {
        FakeTmux tmux = new FakeTmux("unknown");
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertThrows(
                    MalformedResponseException.class, () -> server.cmd("set-option", "-g", "@sentinel", "changed"));
            assertEquals(0, tmux.effects.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.3rc1", "next-3.3a", "3.3a+unknown", "3.3a trailing", "3.3abc"})
    void malformedFullTokensNeverInheritAPrefixsMinimum(String version) {
        FakeTmux tmux = new FakeTmux(version);
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertThrows(
                    MalformedResponseException.class, () -> server.cmd("set-option", "-g", "@sentinel", "changed"));
            assertEquals(0, tmux.effects.get());
        }
    }

    @Test
    void aCandidateForTheMinimumPatchIsStillOlderThanItsRelease() {
        FakeTmux tmux = new FakeTmux("3.3a-rc1");
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertThrows(
                    UnsupportedFeatureException.class, () -> server.cmd("set-option", "-g", "@sentinel", "changed"));
            assertEquals(0, tmux.effects.get());
        }
    }

    @Test
    void rawIfShellFlagsRemainLiteral() {
        List<CommandRequest> dispatched = new ArrayList<>();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))) {
                    return new CommandResult(0, List.of("3.3a"), List.of());
                }
                dispatched.add(request);
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
        List<String> original = List.of("if-shell", "-F", "-b", "1", "set-option -g @sentinel changed", "");
        try (Server server = Server.using(ServerConfig.builder().build(), transport)) {
            assertTrue(server.cmd(original).succeeded());
            assertTrue(dispatched.getFirst().commands().contains(original));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void aVersionCheckNoOpDoesNotTurnReadsIntoMutations(String branch) {
        CommandRequest request = new CommandRequest(
                List.of("tmux"),
                List.of(List.of("if-shell", "-F", "1", branch, "libtmux-stale-version"), List.of("list-sessions")),
                Duration.ofSeconds(1),
                "");
        assertEquals(Idempotence.IDEMPOTENT, request.idempotence());
    }

    @Test
    void aRawCommandFailureCannotImpersonateTheVersionMarker() {
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))
                        ? new CommandResult(0, List.of("3.3a"), List.of())
                        : new CommandResult(1, List.of(), List.of("unknown command: libtmux-stale-version"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), transport)) {
            CommandResult result = server.cmd("libtmux-stale-version");
            assertFalse(result.succeeded());
            assertEquals(List.of("unknown command: libtmux-stale-version"), result.stderr());
        }
    }

    @Test
    void clientVersionDiagnosticsRemainAvailableBelowTheCoreFloor() {
        FakeTmux tmux = new FakeTmux("3.3");
        tmux.binaryVersion = "3.2a";
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertEquals(List.of("tmux 3.2a"), server.cmd("-V").stdout());
            assertEquals(1, tmux.binaryProbes.get());
            assertEquals(0, tmux.effects.get());
        }
    }

    @Test
    void compatibilityPreflightDoesNotTreatADiagnosticAsAVersionCheck() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.absent = true;
        tmux.binaryVersion = "3.3";
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertEquals(List.of("tmux 3.3"), server.cmd("-V").stdout());
            UnsupportedFeatureException failure =
                    assertThrows(UnsupportedFeatureException.class, server::requireCompatibleTmux);
            assertTrue(String.valueOf(failure.getMessage()).contains("configured binary"));
            assertEquals(0, tmux.effects.get());
        }
    }

    @Test
    void compatibilityPreflightUsesTheLiveDaemonAndNeverStartsOne() {
        FakeTmux live = new FakeTmux("3.3a");
        live.binaryVersion = "3.3";
        try (Server server = Server.using(ServerConfig.builder().build(), live)) {
            server.requireCompatibleTmux();
            assertEquals(0, live.binaryProbes.get());
            assertEquals(0, live.effects.get());
        }
        FakeTmux fresh = new FakeTmux("3.3a");
        fresh.absent = true;
        fresh.binaryVersion = "3.3a";
        try (Server server = Server.using(ServerConfig.builder().build(), fresh)) {
            server.requireCompatibleTmux();
            assertEquals(1, fresh.binaryProbes.get());
            assertEquals(0, fresh.effects.get());
        }
    }

    @Test
    void aSupportedDaemonDoesNotTurnClientVersionInspectionIntoAClientMinimum() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.binaryVersion = "3.3";
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            assertEquals(List.of("tmux 3.3"), server.cmd("-V").stdout());
        }
    }

    @Test
    void standaloneHelpPreservesTheNativeDiagnosticBelowTheFloor() {
        List<CommandRequest> seen = new ArrayList<>();
        CommandResult help = new CommandResult(1, List.of(), List.of("usage: tmux [-hV]"));
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                seen.add(request);
                return request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))
                        ? new CommandResult(0, List.of("3.3"), List.of())
                        : help;
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), transport)) {
            assertEquals(help, server.cmd("-h"));
        }
        assertEquals(1, seen.size());
        assertEquals(List.of(List.of("-h")), seen.getFirst().commands());
    }

    @Test
    void aDisappearingDaemonCannotRestartAnOlderConfiguredBinary() {
        AtomicInteger starts = new AtomicInteger();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))) {
                    return new CommandResult(0, List.of("3.3a"), List.of());
                }
                if (request.endpoint().contains("-N")) {
                    return new CommandResult(1, List.of(), List.of("no server running"));
                }
                starts.incrementAndGet();
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
        try (Server server =
                Server.using(ServerConfig.builder().binary("older-tmux").build(), transport)) {
            assertFalse(server.cmd("new-session", "-d", "-s", "blocked").succeeded());
        }
        assertEquals(0, starts.get(), "a live version check must not authorize a fresh daemon");
    }

    @Test
    void aSocketNameEqualToANoStartFlagDoesNotChangeTheVersionCheck() {
        List<CommandRequest> seen = new ArrayList<>();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                seen.add(request);
                if (request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))) {
                    assertEquals("-N", request.endpoint().get(1), "the probe needs a real no-start flag");
                    return new CommandResult(1, List.of(), List.of("no server running"));
                }
                if (request.commands().equals(List.of(List.of("-V")))) {
                    return new CommandResult(0, List.of("tmux 3.3a"), List.of());
                }
                assertEquals(
                        List.of("tmux", "-u", "-L", "-N"), request.endpoint(), "cold creation retains its selector");
                return new CommandResult(0, List.of("created"), List.of());
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("-N"))
                        .build(),
                transport)) {
            assertEquals(List.of("created"), server.cmd("new-session", "-d").stdout());
        }
        assertEquals(3, seen.size(), "daemon probe, binary check and creation");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAbsentProbeCannotAdmitANewlyAppearedOldDaemon(boolean explicitNoStart) {
        AtomicInteger effects = new AtomicInteger();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))) {
                    return new CommandResult(1, List.of(), List.of("no server running"));
                }
                return GroupedTmux.execute(request, 4242, "3.2a", command -> {
                    effects.incrementAndGet();
                    return new CommandResult(0, List.of(), List.of());
                });
            }

            @Override
            public void close() {}
        };
        List<String> endpoint = explicitNoStart ? List.of("tmux", "-N") : List.of("tmux");
        List<String> command =
                explicitNoStart ? List.of("set-option", "-g", "@sentinel", "changed") : List.of("list-sessions");
        CommandRequest request = CommandRequest.of(endpoint, command, Duration.ofSeconds(1));
        assertThrows(TargetGoneException.class, () -> Versions.execute(transport, request, false));
        assertEquals(0, effects.get());
    }

    @Test
    void absentDaemonReadsAndCleanupPreserveNativeResults() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.absent = true;
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            CommandResult result = server.cmd("list-sessions");
            assertFalse(result.succeeded());
            assertEquals(List.of("no server running"), result.stderr());
            server.killServer();
            assertEquals(0, tmux.effects.get());
            assertEquals(0, tmux.binaryProbes.get());
        }
    }

    @Test
    void versionCheckConsumesTheSameDeadlineAsDispatch() {
        FakeTmux tmux = new FakeTmux("3.3a");
        tmux.probeDelay = Duration.ofMillis(30);
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            DispatchException.TimedOut failure = assertThrows(
                    DispatchException.TimedOut.class,
                    () -> server.cmd(List.of("set-option", "-g", "@sentinel", "changed"), Duration.ofMillis(10)));
            assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
            assertTrue(failure.safeToRetry());
            assertEquals(0, tmux.effects.get());
        }
    }

    @Test
    void probeTimeoutDoesNotClaimTheRequestedMutationWasDispatched() {
        FakeTmux tmux = new FakeTmux("3.3a") {
            @Override
            public CommandResult execute(CommandRequest request) {
                throw new DispatchException.TimedOut("probe timed out", DispatchOutcome.UNKNOWN, null);
            }
        };
        try (Server server = Server.using(ServerConfig.builder().build(), tmux)) {
            DispatchException.TimedOut failure =
                    assertThrows(DispatchException.TimedOut.class, () -> server.cmd("kill-server"));
            assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
            assertTrue(failure.safeToRetry());
        }
    }

    private static class FakeTmux implements TmuxTransport {
        private final String reportedVersion;
        private String liveVersion;
        private String binaryVersion = "3.8";
        private boolean absent;
        private CommandResult probeFailure = new CommandResult(0, List.of(), List.of());
        private Duration probeDelay = Duration.ZERO;
        private final AtomicInteger effects = new AtomicInteger();
        private final AtomicInteger binaryProbes = new AtomicInteger();

        FakeTmux(String version) {
            reportedVersion = version;
            liveVersion = version;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            if (request.commands().equals(List.of(List.of("display-message", "-p", "#{version}")))) {
                try {
                    Thread.sleep(probeDelay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                if (!probeFailure.succeeded()) {
                    return probeFailure;
                }
                return absent
                        ? new CommandResult(1, List.of(), List.of("no server running"))
                        : new CommandResult(0, List.of(reportedVersion), List.of());
            }
            if (request.commands().equals(List.of(List.of("-V")))) {
                binaryProbes.incrementAndGet();
                return new CommandResult(0, List.of("tmux " + binaryVersion), List.of());
            }
            if (absent) {
                return new CommandResult(1, List.of(), List.of("no server running"));
            }
            return GroupedTmux.execute(request, 4242, liveVersion, command -> {
                effects.incrementAndGet();
                return new CommandResult(0, List.of("ok"), List.of());
            });
        }

        @Override
        public void close() {}
    }
}
