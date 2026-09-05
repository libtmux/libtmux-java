package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which server the launcher was told to expose. A client passes these once and never sees them
 * again, so a misread flag hands the model a working server that is not the one it named.
 */
final class MainTest {

    @Test
    void nothingSaidPinsTheDedicatedMinimalServer() throws IOException {
        ServerConfig config = Main.configure(List.of());

        assertEquals(ServerEndpoint.namedSocket("libtmux-mcp"), config.endpoint());
        Path minimal = config.configFile().orElseThrow();
        assertTrue(!minimal.equals(Path.of("/dev/null")));
        assertTrue(Files.readString(minimal).contains("libtmux-mcp"));
        assertEquals("tmux", config.binary());
    }

    @Test
    void aSocketPathIsTakenAsAPath() {
        ServerConfig config = Main.configure(List.of("--socket", "/tmp/libtmux-java-dev/probe/s"));

        assertEquals(ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/probe/s")), config.endpoint());
    }

    /** A name is not a path: tmux resolves it under its own directory, which is the whole difference. */
    @Test
    void aSocketNameIsTakenAsAName() {
        ServerConfig config = Main.configure(List.of("--socket-name", "work"));

        assertEquals(ServerEndpoint.namedSocket("work"), config.endpoint());
    }

    @Test
    void theBinaryCanBeChosenAlongsideTheServer() {
        ServerConfig config = Main.configure(List.of("--socket-name", "work", "--tmux", "/usr/local/bin/tmux"));

        assertEquals(ServerEndpoint.namedSocket("work"), config.endpoint());
        assertEquals("/usr/local/bin/tmux", config.binary());
    }

    @Test
    void socketFlagsAreMutuallyExclusive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Main.configure(List.of("--socket", "/tmp/libtmux-java-dev/probe/s", "--socket-name", "work")));
    }

    @Test
    void aFlagNobodyRecognisesStopsTheLauncherRatherThanBeingIgnored() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Main.configure(List.of("--sokcet", "/tmp/s")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("--sokcet"), message);
    }

    /**
     * A flag whose value is missing would otherwise swallow the next flag as its value, and the
     * launcher would serve a server nobody asked for.
     */
    @Test
    void aFlagWithNoValueSaysWhichFlagIsMissingIt() {
        for (String flag : List.of("--socket", "--socket-name", "--tmux")) {
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> Main.configure(List.of(flag)));

            String message = String.valueOf(refused.getMessage());
            assertTrue(message.contains(flag), message);
        }
    }

    /** A value that looks like a flag is still a value; only its position decides. */
    @Test
    void aValueIsTakenLiterallyEvenWhenItLooksLikeAFlag() {
        ServerConfig config = Main.configure(List.of("--socket-name", "--tmux"));

        assertEquals(ServerEndpoint.namedSocket("--tmux"), config.endpoint());
    }

    @Test
    void theRetiredSafetyFlagNamesItsReplacement() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Main.configure(List.of("--safety", "readonly")));

        assertTrue(String.valueOf(refused.getMessage()).contains("LIBTMUX_TOOLSETS"));
    }

    @Test
    void socketAndConfigurationEnvironmentAreResolvedOnceAtStartup() {
        LaunchConfiguration named =
                LaunchConfiguration.resolve(List.of(), Map.of(LaunchConfiguration.SOCKET_ENV, "work"));
        assertEquals(ServerEndpoint.namedSocket("work"), named.config().endpoint());
        assertTrue(named.config().configFile().isEmpty());
        assertEquals("unknown", named.declaredConfigurationProvenance());
        assertEquals(false, named.defaultDedicated());

        LaunchConfiguration path = LaunchConfiguration.resolve(
                List.of(),
                Map.of(
                        LaunchConfiguration.SOCKET_PATH_ENV,
                        "/tmp/libtmux-java-dev/probe/s",
                        LaunchConfiguration.CONFIG_ENV,
                        "/tmp/libtmux-java-dev/minimal.conf"));
        assertEquals(
                ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/probe/s")),
                path.config().endpoint());
        assertEquals(
                Path.of("/tmp/libtmux-java-dev/minimal.conf"),
                path.config().configFile().orElseThrow());
        assertEquals("user-configured", path.declaredConfigurationProvenance());
        SocketProfile absent = absentProfile(path);
        assertEquals("absent", absent.serverState());
        assertEquals("user-configured", absent.configurationProvenance());
        assertThrows(
                IllegalArgumentException.class,
                () -> LaunchConfiguration.resolve(
                        List.of(),
                        Map.of(
                                LaunchConfiguration.SOCKET_ENV,
                                "work",
                                LaunchConfiguration.SOCKET_PATH_ENV,
                                "/tmp/libtmux-java-dev/probe/s")));
    }

    @Test
    void onlyANewDefaultMinimalSocketEnablesTeardownByDefault() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of(), Map.of());

        SocketProfile created = profile(launch, Objects.requireNonNull(launch.ownerNonce()));
        SocketProfile existing = profile(launch, "another-launch");
        assertEquals(true, created.defaultTeardown());
        assertEquals("minimal", created.configurationProvenance());
        assertEquals("created", created.serverState());
        assertEquals(false, existing.defaultTeardown());
        assertEquals("unknown", existing.configurationProvenance());
        assertEquals("existing", existing.serverState());

        assertEquals(45, ToolSurface.resolve(Map.of(), created).tools().size());
        assertEquals(41, ToolSurface.resolve(Map.of(), existing).tools().size());
        assertEquals(false, ToolSurface.resolve(Map.of(), existing).tools().containsKey("kill_session"));
        assertEquals(
                List.of("kill_pane", "kill_window", "kill_session"),
                ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "teardown"), existing).tools().keySet().stream()
                        .filter(name -> name.startsWith("kill_"))
                        .toList());
    }

    @Test
    void aPrivateLaunchNonceProvesCreationWithoutEnteringTheTmuxEnvironment() throws IOException {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of(), Map.of());
        String config = Files.readString(launch.config().configFile().orElseThrow());

        assertTrue(config.contains(launch.ownerNonce()));
        assertTrue(config.contains("set -g exit-empty off"));
        assertTrue(!config.contains("set-environment"));
        assertTrue(!config.contains("LIBTMUX_"));
    }

    @Test
    void onlyTheLaunchWhosePrivateConfigCreatedTheDaemonOwnsIt() throws IOException {
        Path root = Path.of("/tmp/libtmux-java-test");
        Files.createDirectories(root);
        Path socket = root.resolve("mcp-owner-" + UUID.randomUUID());
        LaunchConfiguration first = dedicatedOn(LaunchConfiguration.resolve(List.of(), Map.of()), socket);
        LaunchConfiguration second = dedicatedOn(LaunchConfiguration.resolve(List.of(), Map.of()), socket);

        try (Server firstServer = Server.open(first.config())) {
            try {
                SocketProfile created = first.profile(firstServer);
                assertEquals("created", created.serverState());
                assertTrue(created.defaultTeardown());

                try (Server secondServer = Server.open(second.config())) {
                    SocketProfile existing = second.profile(secondServer);
                    assertEquals("existing", existing.serverState());
                    assertTrue(!existing.defaultTeardown());
                    CommandResult environment = secondServer.cmd("show-environment", "-g");
                    assertTrue(environment.succeeded(), environment.stderr().toString());
                    assertTrue(environment.stdout().stream()
                            .noneMatch(line -> line.contains(Objects.requireNonNull(first.ownerNonce()))
                                    || line.contains(Objects.requireNonNull(second.ownerNonce()))));
                }
            } finally {
                if (firstServer.isAlive()) {
                    firstServer.killServer();
                }
            }
        }
    }

    @Test
    void missingStartupMetadataFailsClosed() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of(), Map.of());
        TmuxTransport broken = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                List<String> command = request.commands().getFirst();
                return command.getFirst().equals("start-server")
                        ? new CommandResult(0, List.of(), List.of())
                        : new CommandResult(1, List.of(), List.of("metadata unavailable"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), broken)) {
            assertThrows(IllegalStateException.class, () -> launch.profile(server));
        }
    }

    @Test
    void explicitSocketMetadataErrorsAreNotMisreportedAsAnAbsentServer() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(
                List.of(), Map.of(LaunchConfiguration.SOCKET_PATH_ENV, "/tmp/libtmux-java-dev/denied/s"));
        TmuxTransport denied = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(1, List.of(), List.of("permission denied"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), denied)) {
            assertThrows(IllegalStateException.class, () -> launch.profile(server));
        }
    }

    @Test
    void malformedSocketAndConfigurationSelectorsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LaunchConfiguration.resolve(
                        List.of(), Map.of(LaunchConfiguration.SOCKET_PATH_ENV, "relative/socket")));
        assertThrows(
                IllegalArgumentException.class,
                () -> LaunchConfiguration.resolve(List.of(), Map.of(LaunchConfiguration.CONFIG_ENV, "relative.conf")));
        assertThrows(
                IllegalArgumentException.class,
                () -> LaunchConfiguration.resolve(List.of(), Map.of(LaunchConfiguration.CONFIG_ENV, "minimal")));
        assertThrows(
                IllegalArgumentException.class,
                () -> LaunchConfiguration.resolve(List.of(), Map.of(LaunchConfiguration.CONFIG_ENV, "")));
    }

    private static SocketProfile profile(LaunchConfiguration launch, String marker) {
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                List<String> command = request.commands().getFirst();
                if (command.getFirst().equals("start-server")) {
                    return new CommandResult(0, List.of(), List.of());
                }
                if (command.getFirst().equals("display-message")) {
                    return new CommandResult(
                            0, List.of(marker + "\t/tmp/libtmux-java-dev/libtmux-mcp.sock"), List.of());
                }
                throw new AssertionError(command);
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), transport)) {
            return launch.profile(server);
        }
    }

    private static LaunchConfiguration dedicatedOn(LaunchConfiguration launch, Path socket) {
        return new LaunchConfiguration(
                launch.config().toBuilder()
                        .endpoint(ServerEndpoint.socketPath(socket))
                        .build(),
                "path:" + socket,
                "default-dedicated",
                launch.declaredConfigurationProvenance(),
                true,
                launch.ownerNonce());
    }

    private static SocketProfile absentProfile(LaunchConfiguration launch) {
        TmuxTransport absent = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(1, List.of(), List.of("no server running on /tmp/libtmux-java-dev/probe/s"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), absent)) {
            return launch.profile(server);
        }
    }
}
