package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.internal.ControlAcquisition;
import io.github.libtmux.junit5.NamedServerFixture;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.ProcessTransport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Native acquisition must retain its socket before starting a control process. */
@Tag("control")
@ExtendWith(TmuxExtension.class)
final class ControlAcquisitionIntegrationTest {
    private static final Duration LIMIT = Duration.ofSeconds(5);

    @BeforeEach
    void requireControl(Server server) {
        ControlSupport.require(server);
    }

    @Test
    void replacingTheOriginalSocketCannotAcquireTheReplacement(Server server) throws Exception {
        var snapshot = server.snapshot();
        var session = server.sessions().getFirst();
        AtomicReference<Path> alias = new AtomicReference<>();
        AtomicBoolean attempted = new AtomicBoolean();
        try (ProcessTransport transport = new ProcessTransport();
                Server replacement = Server.open(server.config())) {
            ControlCarrier local = transport.controlCarrier().orElseThrow();
            ControlCarrier racing = new ControlCarrier() {
                @Override
                public PinnedSocket pinSocket(String socket, Duration timeout) throws IOException {
                    PinnedSocket pin = local.pinSocket(socket, timeout);
                    alias.set(Path.of(pin.socketPath()));
                    return pin;
                }

                @Override
                public Process start(List<String> command) throws IOException {
                    if (command.contains("-C")) {
                        attempted.set(true);
                        server.killServer();
                        replacement.newSession("replacement");
                        replacement
                                .hooks()
                                .set("client-attached", List.of("set-option", "-g", "@unexpected-control", "yes"));
                    }
                    return local.start(command);
                }
            };
            try {
                assertThrows(
                        LibTmuxException.class,
                        () -> ControlClient.attach(
                                racing,
                                server.config(),
                                session.id(),
                                snapshot.serverPid().orElseThrow(),
                                snapshot.serverStartTime(),
                                server.version().toString(),
                                LIMIT));
                assertTrue(attempted.get(), "the socket was not replaced immediately before -C");
                assertTrue(replacement.isAlive());
                assertTrue(replacement.clients().isEmpty());
                assertTrue(
                        replacement.globalOptions().get("@unexpected-control").isEmpty(),
                        "the replacement registered a control client before the post-attach fence");
                assertTrue(Files.notExists(alias.get()), "the refused attachment retained its alias");
                assertTrue(Files.notExists(alias.get().getParent()));
                assertTrue(
                        transport
                                .execute(CommandRequest.of(
                                        replacement.config().endpointCommand(),
                                        List.of("display-message", "-p", "borrowed"),
                                        LIMIT))
                                .succeeded(),
                        "closing the client closed its borrowed carrier");
            } finally {
                replacement.killServer();
            }
        }
    }

    @Test
    void daemonExitRemovesThePinBeforeExplicitClientClose(Server server) throws Exception {
        AtomicReference<Path> alias = new AtomicReference<>();
        try (ProcessTransport transport = new ProcessTransport()) {
            ControlCarrier local = transport.controlCarrier().orElseThrow();
            ControlCarrier counted = new ControlCarrier() {
                @Override
                public Process start(List<String> command) throws IOException {
                    return local.start(command);
                }

                @Override
                public PinnedSocket pinSocket(String socket, Duration timeout) throws IOException {
                    PinnedSocket pin = local.pinSocket(socket, timeout);
                    alias.set(Path.of(pin.socketPath()));
                    return pin;
                }
            };
            var snapshot = server.snapshot();
            try (ControlClient client = ControlClient.attach(
                    counted,
                    server.config(),
                    server.sessions().getFirst().id(),
                    snapshot.serverPid().orElseThrow(),
                    snapshot.serverStartTime(),
                    server.version().toString(),
                    LIMIT)) {
                assertTrue(Files.exists(alias.get()));
                server.killServer();
                assertTrue(Await.until(() -> Files.notExists(alias.get())), "natural EOF leaked the pin");
                assertTrue(Files.notExists(alias.get().getParent()));
                assertFalse(client.isAlive());
            }
        }
    }

    @Test
    void aValidLongSocketCanUseAShortAlias(Server fixture) throws Exception {
        Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "long-pin-");
        int padding = 100 - root.toString().getBytes(StandardCharsets.UTF_8).length - 3;
        Path socket = Files.createDirectory(root.resolve("x".repeat(padding))).resolve("s");
        assertEquals(100, socket.toString().getBytes(StandardCharsets.UTF_8).length);
        ServerConfig config = ServerConfig.builder()
                .binary(fixture.config().binary())
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(Path.of("/dev/null"))
                .build();
        try (Server server = Server.open(config)) {
            try {
                server.newSession("long-socket");
                try (NamedServerFixture owned = NamedServerFixture.own(server, socket, root);
                        ControlClient client = server.control(server.sessions().getFirst())) {
                    assertEquals(socket.toRealPath(), owned.socket());
                    assertEquals(
                            List.of("long-path"),
                            client.send("display-message", "-p", "long-path").lines());
                }
            } finally {
                server.killServer();
            }
        } finally {
            Files.deleteIfExists(root);
        }
    }

    /** The identity travels in one word, so a socket path with a space and a quote must survive it. */
    @Test
    void anIdentityKeepsSpacesAndQuotesInTheSocketPath(Server fixture) throws Exception {
        Path root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "quoted-");
        Path directory = Files.createDirectory(root.resolve("it's a dir"));
        Path socket = directory.resolve("a socket, with comma");
        ServerConfig config = ServerConfig.builder()
                .binary(fixture.config().binary())
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(Path.of("/dev/null"))
                .build();
        try (Server server = Server.open(config)) {
            try {
                server.newSession("quoted-socket");
                try (ProcessTransport processes = new ProcessTransport();
                        ControlAcquisition acquisition = ControlAcquisition.acquire(
                                processes.controlCarrier().orElseThrow(), config, Duration.ofSeconds(30))) {
                    assertEquals(socket.toString(), acquisition.identity().socket());
                }
            } finally {
                server.killServer();
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(directory);
            Files.deleteIfExists(root);
        }
    }

    /**
     * Qualifying a daemon for control reads its version and identity, rechecks both through the
     * pin, and a user's {@code after-display-message} hook must not run for any of it. (Control
     * replies are framed with {@code display-message} markers once a client is attached; that is
     * the client's own traffic and is not part of acquisition.) The last step fires the hook the
     * ordinary way, so a quiet record means the hook was live and not run.
     */
    @Test
    void qualifyingTheDaemonRunsNoAfterDisplayMessageHook(Server server) throws Exception {
        assertTrue(server.cmd("set-hook", "-g", "after-display-message", "set-option -g @acquire-hook fired")
                .succeeded());
        try (ProcessTransport processes = new ProcessTransport();
                ControlAcquisition acquisition = ControlAcquisition.acquire(
                        processes.controlCarrier().orElseThrow(), server.config(), Duration.ofSeconds(30))) {
            acquisition.recheck(processes.controlCarrier().orElseThrow());
            assertTrue(acquisition.identity().pid() > 0);
        }

        assertEquals(
                List.of(), server.cmd("show-options", "-gqv", "@acquire-hook").stdout(), "acquisition ran the hook");
        assertTrue(server.cmd("display-message", "-p", "control").succeeded());
        assertEquals(
                List.of("fired"),
                server.cmd("show-options", "-gqv", "@acquire-hook").stdout(),
                "the hook never fires, so the check above proves nothing");
    }
}
