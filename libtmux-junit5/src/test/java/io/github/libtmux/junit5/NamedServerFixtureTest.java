package io.github.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NamedServerFixtureTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    @Test
    void theSameNamedEndpointCanBeOwnedTwice(@TempDir Path directory) throws Exception {
        String name = "ltj-owned-" + ProcessHandle.current().pid();
        Path previous = null;

        for (int run = 0; run < 2; run++) {
            Path socket;
            try (Server server = openNamed(name, directory)) {
                server.newSession("owned-" + run);
                socket = reportedSocket(server);
                try (NamedServerFixture fixture = NamedServerFixture.own(server, name, quarantine())) {
                    assertEquals(socket, fixture.socket());
                    assertTrue(server.hasSession("owned-" + run));
                }
            }
            assertFalse(Files.exists(socket), "the owned named socket survived teardown");
            if (previous != null) {
                assertEquals(previous, socket, "the second run did not reuse the same namespace");
            }
            previous = socket;
        }
    }

    @Test
    void theSameExplicitEndpointCanBeOwnedTwice(@TempDir Path directory) throws Exception {
        Path socket =
                quarantine().resolve("ltj-explicit-" + ProcessHandle.current().pid());

        for (int run = 0; run < 2; run++) {
            try (Server server = openPath(socket, directory)) {
                server.newSession("explicit-" + run);
                try (NamedServerFixture fixture = NamedServerFixture.own(server, socket, quarantine())) {
                    assertEquals(socket, fixture.socket());
                    assertTrue(server.hasSession("explicit-" + run));
                }
            }
            assertFalse(Files.exists(socket), "the explicit socket survived teardown");
        }
    }

    @Test
    void aReplacementSentinelIsNeverRemoved(@TempDir Path directory) throws Exception {
        String name = "ltj-sentinel-" + ProcessHandle.current().pid();
        Path socket = null;
        try (Server server = openNamed(name, directory)) {
            server.newSession("sentinel");
            socket = reportedSocket(server);
            ProcessHandle process = reportedProcess(server);
            NamedServerFixture fixture = NamedServerFixture.own(server, name, quarantine());

            server.killServer();
            awaitExit(process);
            Files.delete(socket);
            Files.writeString(socket, "keep");

            Path planted = socket;
            AssertionError refused = assertThrows(AssertionError.class, fixture::close);
            String message = String.valueOf(refused.getMessage());
            assertTrue(message.contains("non-socket replacement"), message);
            assertEquals("keep", Files.readString(planted));
        } finally {
            if (socket != null) {
                Files.deleteIfExists(socket);
            }
        }
    }

    @Test
    void aLiveReplacementServerSurvivesStaleFixtureCleanup(@TempDir Path directory) throws Exception {
        String name = "ltj-replacement-" + ProcessHandle.current().pid();
        Path socket;
        NamedServerFixture originalFixture;
        try (Server original = openNamed(name, directory)) {
            original.newSession("original");
            socket = reportedSocket(original);
            ProcessHandle originalProcess = reportedProcess(original);
            originalFixture = NamedServerFixture.own(original, name, quarantine());

            original.killServer();
            awaitExit(originalProcess);
            Files.delete(socket);

            try (Server replacement = openNamed(name, directory)) {
                replacement.newSession("replacement");
                ProcessHandle replacementProcess = reportedProcess(replacement);
                NamedServerFixture replacementFixture = NamedServerFixture.own(replacement, name, quarantine());
                try {
                    assertEquals(socket, replacementFixture.socket());
                    AssertionError refused = assertThrows(AssertionError.class, originalFixture::close);

                    assertTrue(String.valueOf(refused.getMessage()).contains("replacement"));
                    assertTrue(replacement.hasSession("replacement"));
                } finally {
                    replacementFixture.close();
                }
                assertFalse(replacementProcess.isAlive(), "replacement cleanup did not end its process");
                assertFalse(Files.exists(socket), "replacement cleanup left its socket");
            }
        }
    }

    @Test
    void replacementBetweenIdentityReadAndKillSurvives(@TempDir Path directory) throws Exception {
        String suffix = Long.toString(ProcessHandle.current().pid());
        String originalName = "ltj-race-original-" + suffix;
        String replacementName = "ltj-race-replacement-" + suffix;

        try (Server original = openNamed(originalName, directory);
                Server replacement = openNamed(replacementName, directory)) {
            original.newSession("race-original");
            replacement.newSession("race-replacement");
            NamedServerFixture originalCleanup = NamedServerFixture.own(original, originalName, quarantine());
            try {
                NamedServerFixture replacementCleanup =
                        NamedServerFixture.own(replacement, replacementName, quarantine());
                try {
                    List<CommandRequest> requests = new ArrayList<>();
                    int[] identityReads = {0};
                    TmuxTransport switching = new TmuxTransport() {
                        @Override
                        public CommandResult execute(CommandRequest request) {
                            requests.add(request);
                            List<String> command = request.commands().getFirst();
                            Server destination = command.getFirst().equals("display-message") && identityReads[0]++ < 2
                                    ? original
                                    : replacement;
                            return destination.cmd(command);
                        }

                        @Override
                        public void close() {}
                    };

                    try (Server routed = Server.using(original.config(), switching)) {
                        NamedServerFixture stale = NamedServerFixture.own(routed, originalName, quarantine());
                        AssertionError refused = assertThrows(AssertionError.class, stale::close);

                        assertTrue(String.valueOf(refused.getMessage()).contains("replacement"));
                        assertTrue(replacement.hasSession("race-replacement"));
                        assertEquals(
                                List.of("display-message", "if-shell"),
                                requests.stream()
                                        .map(request ->
                                                request.commands().getFirst().getFirst())
                                        .toList());
                    }
                } finally {
                    replacementCleanup.close();
                }
            } finally {
                originalCleanup.close();
            }
        }
    }

    @Test
    void failedAuthenticationNeverSendsAnEndpointCommand(@TempDir Path directory) throws Exception {
        Path sentinel = directory.resolve("not-a-socket");
        Files.writeString(sentinel, "keep");
        List<CommandRequest> requests = new ArrayList<>();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                requests.add(request);
                if (request.commands().getFirst().getFirst().equals("display-message")) {
                    return new CommandResult(0, List.of("malformed"), List.of());
                }
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
        ServerConfig config = ServerConfig.builder()
                .binary(TMUX)
                .endpoint(ServerEndpoint.socketPath(sentinel))
                .build();

        try (Server server = Server.using(config, transport)) {
            AssertionError refused =
                    assertThrows(AssertionError.class, () -> NamedServerFixture.own(server, "expected", directory));

            assertTrue(String.valueOf(refused.getMessage()).contains("malformed identity row"));
        }
        assertEquals("keep", Files.readString(sentinel));
        assertEquals(1, requests.size());
        assertEquals(
                List.of("display-message", "-p", "#{pid}\t#{socket_path}"),
                requests.get(0).commands().getFirst());
        assertTrue(requests.stream().allMatch(request -> request.endpoint().contains(sentinel.toString())));
    }

    private static Server openNamed(String name, Path directory) throws IOException {
        Path config = directory.resolve(name + ".conf");
        Files.writeString(config, "");
        return Server.open(ServerConfig.builder()
                .binary(TMUX)
                .endpoint(ServerEndpoint.namedSocket(name))
                .configFile(config)
                .build());
    }

    private static Server openPath(Path socket, Path directory) throws IOException {
        Path config = directory.resolve(socket.getFileName() + ".conf");
        Files.writeString(config, "");
        return Server.open(ServerConfig.builder()
                .binary(TMUX)
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(config)
                .build());
    }

    private static Path reportedSocket(Server server) {
        List<String> rows =
                server.cmd("display-message", "-p", "#{socket_path}").stdout();
        assertEquals(1, rows.size(), "tmux did not report one socket path");
        return Path.of(rows.getFirst());
    }

    private static ProcessHandle reportedProcess(Server server) {
        List<String> rows = server.cmd("display-message", "-p", "#{pid}").stdout();
        assertEquals(1, rows.size(), "tmux did not report one process id");
        long pid = Long.parseLong(rows.getFirst());
        return ProcessHandle.of(pid).orElseThrow(() -> new AssertionError("the named tmux process was not found"));
    }

    private static Path quarantine() {
        String configured = System.getenv("TMUX_TMPDIR");
        assertTrue(configured != null && !configured.isEmpty(), "the build did not quarantine TMUX_TMPDIR");
        return Path.of(configured);
    }

    private static void awaitExit(ProcessHandle process) throws Exception {
        process.onExit().get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        assertFalse(process.isAlive(), "the named tmux process did not exit");
    }
}
