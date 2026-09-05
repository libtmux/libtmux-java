package io.github.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
                try (NamedServerFixture replacementFixture = NamedServerFixture.own(replacement, name, quarantine())) {
                    assertEquals(socket, replacementFixture.socket());
                    AssertionError refused = assertTimeoutPreemptively(
                            Duration.ofSeconds(2), () -> assertThrows(AssertionError.class, originalFixture::close));

                    assertTrue(String.valueOf(refused.getMessage()).contains("replacement"));
                    assertTrue(replacement.hasSession("replacement"));
                }
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
