package io.github.libtmux.mcp;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which server the launcher was told to expose. A client passes these once and never sees them
 * again, so a misread flag hands the model a working server that is not the one it named.
 */
final class MainTest {

    /**
     * The launcher once started a server of its own on the socket {@code libtmux-mcp}, which sent an
     * agent started from inside tmux to a server nobody was looking at. Saying nothing now passes
     * tmux no socket and no configuration file, so tmux picks the server.
     */
    @Test
    void nothingSaidPassesTmuxNoSocketAndNoConfigurationFile() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of(), Map.of());

        assertEquals(ServerEndpoint.defaultSocket(), launch.config().endpoint());
        assertEquals(List.of("tmux", "-u"), launch.config().endpointCommand());
        assertTrue(launch.config().configFile().isEmpty());
        assertEquals("inherit", launch.selector());
        assertEquals("inherited", launch.selectionProvenance());
        assertEquals("unknown", launch.declaredConfigurationProvenance());
    }

    /** {@code TMUX} is tmux's to follow, so it is never read into a socket here. */
    @Test
    void theTmuxVariableIsNotReadAsASocket() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(
                List.of(), Map.of("TMUX", "/tmp/libtmux-java-test/inside/s,1,0", "TMUX_PANE", "%0"));

        assertEquals(ServerEndpoint.defaultSocket(), launch.config().endpoint());
    }

    /**
     * A tmux that reports an old or unreadable release stops startup with one line on stderr and a
     * non-zero status. The remedy is in that line; a stack trace would bury it.
     */
    @ParameterizedTest
    @CsvSource({"3.2a,requires tmux 3.3a or newer", "master,unrecognized version: master"})
    void aTmuxTheFloorRefusesStopsStartupWithOneLine(String reported, String expected, @TempDir Path dir)
            throws IOException {
        Path binary = dir.resolve("tmux");
        Files.writeString(binary, """
                #!/bin/sh
                for argument in "$@"; do
                  case "$argument" in
                    -V) echo "tmux %1$s"; exit 0 ;;
                    libtmux-version-*)
                      printf 'unknown command: %%s\\n' "${argument%%'#{version}'}%1$s" >&2; exit 1 ;;
                  esac
                done
                exit 0
                """.formatted(reported));
        Files.setPosixFilePermissions(binary, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        int status = Main.run(
                new String[] {"--socket", dir.resolve("socket").toString(), "--tmux", binary.toString()},
                Map.of(),
                InputStream.nullInputStream(),
                new PrintStream(captured, true, StandardCharsets.UTF_8));

        String stderr = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1, status);
        assertTrue(stderr.startsWith("libtmux-mcp: "), stderr);
        assertTrue(stderr.contains(expected), stderr);
        assertEquals(1, stderr.lines().count(), stderr);
        assertFalse(stderr.contains("Exception"), stderr);
    }

    /** With no daemon running the binary itself is checked, so the refusal still comes at startup. */
    @Test
    void aColdBinaryBelowTheFloorStopsStartupToo(@TempDir Path dir) throws IOException {
        Path binary = dir.resolve("tmux");
        Files.writeString(binary, """
                #!/bin/sh
                for argument in "$@"; do
                  case "$argument" in
                    -V) echo "tmux 3.2a"; exit 0 ;;
                  esac
                done
                echo "no server running on socket" >&2
                exit 1
                """);
        Files.setPosixFilePermissions(binary, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        int status = Main.run(
                new String[] {"--socket", dir.resolve("socket").toString(), "--tmux", binary.toString()},
                Map.of(),
                InputStream.nullInputStream(),
                new PrintStream(captured, true, StandardCharsets.UTF_8));

        String stderr = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1, status);
        assertTrue(stderr.contains("requires tmux 3.3a or newer"), stderr);
        assertFalse(stderr.contains("serving"), stderr);
        assertEquals(1, stderr.lines().count(), stderr);
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

    /**
     * The attach command an operator is handed comes from the endpoint's own flags.
     *
     * <p>It used to be re-derived in two places, each an {@code instanceof} chain that named
     * {@code -S} and {@code -L} itself. A kind of endpoint those chains had not heard of produced a
     * command missing the flag that selects the server — that is, one that attaches to the wrong
     * tmux. Deriving it from {@link ServerEndpoint#flags()} is what makes every kind covered by
     * construction, so every kind is asserted here rather than only the two that existed.
     */
    @Test
    void everyKindOfEndpointReachesTheAttachCommand() {
        assertEquals(" -N attach", attachFlags(ServerEndpoint.defaultSocket()));
        assertEquals(" -N -L work attach", attachFlags(ServerEndpoint.namedSocket("work")));
        assertEquals(
                " -N -S /tmp/libtmux-java-dev/probe/s attach",
                attachFlags(ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/probe/s"))));

        assertEquals(
                List.of("-S", "/tmp/libtmux-java-dev/probe/s"),
                ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/probe/s"))
                        .flags(),
                "the command is these flags, so a change to them has to show up above");
    }

    /** A path a shell would mangle is quoted; one it reads as itself is left legible. */
    @Test
    void onlyAnUnsafePathIsQuotedInTheAttachCommand() {
        assertEquals(
                " -N -S '/tmp/libtmux-java-dev/od(d)/s' attach",
                attachFlags(ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/od(d)/s"))));
    }

    /**
     * A socket path tmux reported wins over the name that found it.
     *
     * <p>{@code -L} is resolved again under whoever runs the command, whose {@code TMUX_TMPDIR} may
     * name a different directory and so a different server with the same name. The absolute path tmux
     * reported reaches this one from anywhere.
     */
    @Test
    void aReportedSocketPathWinsOverTheNameThatFoundIt() {
        ServerConfig named = ServerConfig.builder()
                .endpoint(ServerEndpoint.namedSocket("work"))
                .build();
        String whole = SocketProfile.attachCommand(named, "/tmp/libtmux-java-dev/elsewhere/work");

        assertEquals(" -N -S /tmp/libtmux-java-dev/elsewhere/work attach", whole.substring(whole.indexOf(" -N")));
    }

    /**
     * The attach command from {@code -N} onwards, so an assertion is about the endpoint rather than
     * about where tmux happens to be installed on the machine running the suite.
     */
    private static String attachFlags(ServerEndpoint endpoint) {
        ServerConfig config = ServerConfig.builder().endpoint(endpoint).build();
        String whole = SocketProfile.attachCommand(config, "");
        assertTrue(whole.contains(config.binaryPath()), whole);
        return whole.substring(whole.indexOf(" -N"));
    }

    @Test
    void socketAndConfigurationEnvironmentAreResolvedOnceAtStartup() {
        LaunchConfiguration named =
                LaunchConfiguration.resolve(List.of(), Map.of(LaunchConfiguration.SOCKET_ENV, "work"));
        assertEquals(ServerEndpoint.namedSocket("work"), named.config().endpoint());
        assertTrue(named.config().configFile().isEmpty());
        assertEquals("unknown", named.declaredConfigurationProvenance());

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
    }

    /**
     * The Python libtmux builds {@code Server(socket_name, socket_path)} from the two variables and
     * keeps only the path when it is given both. The launcher used to refuse the pair.
     */
    @Test
    void thePathVariableWinsWhenBothAreSet() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(
                List.of(),
                Map.of(
                        LaunchConfiguration.SOCKET_ENV,
                        "work",
                        LaunchConfiguration.SOCKET_PATH_ENV,
                        "/tmp/libtmux-java-dev/probe/s"));

        assertEquals(
                ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/probe/s")),
                launch.config().endpoint());
        assertEquals("path:/tmp/libtmux-java-dev/probe/s", launch.selector());
    }

    /** Each variable is judged alone, and an empty one is the Python libtmux's falsy value: unset. */
    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void aBlankVariableCountsAsUnset(String blank) {
        assertEquals(
                ServerEndpoint.defaultSocket(),
                LaunchConfiguration.resolve(
                                List.of(),
                                Map.of(
                                        LaunchConfiguration.SOCKET_ENV,
                                        blank,
                                        LaunchConfiguration.SOCKET_PATH_ENV,
                                        blank))
                        .config()
                        .endpoint());
        assertEquals(
                ServerEndpoint.namedSocket("work"),
                LaunchConfiguration.resolve(
                                List.of(),
                                Map.of(
                                        LaunchConfiguration.SOCKET_ENV,
                                        "work",
                                        LaunchConfiguration.SOCKET_PATH_ENV,
                                        blank))
                        .config()
                        .endpoint());
    }

    @Test
    void aFlagBeatsBothVariables() {
        Map<String, String> both = Map.of(
                LaunchConfiguration.SOCKET_ENV,
                "work",
                LaunchConfiguration.SOCKET_PATH_ENV,
                "/tmp/libtmux-java-dev/env/s");

        assertEquals(
                ServerEndpoint.namedSocket("flagged"),
                LaunchConfiguration.resolve(List.of("--socket-name", "flagged"), both)
                        .config()
                        .endpoint());
        assertEquals(
                ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/flag/s")),
                LaunchConfiguration.resolve(List.of("--socket", "/tmp/libtmux-java-dev/flag/s"), both)
                        .config()
                        .endpoint());
    }

    /**
     * A new default server used to enable all four toolsets, because the launcher had created it. The
     * launcher creates nothing now, so every launch leaves {@code teardown} out until it is asked for.
     */
    @Test
    void noLaunchEnablesTeardownByDefault() {
        SocketProfile inherited = profile(LaunchConfiguration.resolve(List.of(), Map.of()));
        SocketProfile chosen =
                profile(LaunchConfiguration.resolve(List.of(), Map.of(LaunchConfiguration.SOCKET_ENV, "work")));

        for (SocketProfile profile : List.of(inherited, chosen)) {
            assertEquals(41, ToolSurface.resolve(Map.of(), profile).tools().size());
            assertEquals(false, ToolSurface.resolve(Map.of(), profile).tools().containsKey("kill_session"));
            assertEquals(
                    List.of("kill_pane", "kill_window", "kill_session"),
                    ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "teardown"), profile).tools().keySet().stream()
                            .filter(name -> name.startsWith("kill_"))
                            .toList());
        }
    }

    /** The launcher does not start tmux or write to it; it only asks which socket it reached. */
    @Test
    void startupOnlyAsksTheServerWhereItsSocketIs() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of(), Map.of());
        List<String> sent = new java.util.ArrayList<>();
        Path socket = reportableSocket();
        TmuxTransport recording = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (VersionProbes.is(request)) {
                    return VersionProbes.reply(request, "3.6");
                }
                sent.add(request.commands().getLast().getFirst());
                return new CommandResult(0, List.of(socket.toString()), List.of());
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), recording)) {
            SocketProfile profile = launch.profile(server);

            assertEquals(List.of("display-message"), sent);
            assertEquals("existing", profile.serverState());
            assertEquals("unknown", profile.configurationProvenance());
        }
    }

    @Test
    void missingStartupMetadataFailsClosed() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of(), Map.of());
        TmuxTransport broken = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (VersionProbes.is(request)) {
                    return VersionProbes.reply(request, "3.6");
                }
                return new CommandResult(1, List.of(), List.of("metadata unavailable"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), broken)) {
            assertThrows(IllegalStateException.class, () -> launch.profile(server));
        }
    }

    @Test
    void aReportedSocketPathThatNamesNoFileFailsClosed() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(
                List.of(), Map.of(LaunchConfiguration.SOCKET_PATH_ENV, "/tmp/libtmux-java-dev/escaped/s"));
        // tmux 3.4 and 3.5 render a non-printable byte in the socket path before a format reads it,
        // so the answer carries no control byte for RouteValue to refuse and still names no file.
        TmuxTransport escaping = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (VersionProbes.is(request)) {
                    return VersionProbes.reply(request, "3.6");
                }
                return new CommandResult(0, List.of("/tmp/libtmux-java-dev/escaped\\001/s"), List.of());
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(launch.config(), escaping)) {
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
                if (VersionProbes.is(request)) {
                    return VersionProbes.reply(request, "3.6");
                }
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

    @Test
    void startupRejectsControlCharactersInClientRoutes() {
        for (int value : IntStream.concat(IntStream.rangeClosed(0, 0x1f), IntStream.of(0x7f))
                .toArray()) {
            String control = Character.toString(value);
            String label = String.format("U+%04X", value);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LaunchConfiguration.resolve(List.of("--tmux", "tmux" + control), Map.of()),
                    label + " executable");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LaunchConfiguration.resolve(List.of("--socket-name", "socket" + control), Map.of()),
                    label + " socket name");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LaunchConfiguration.resolve(List.of("--socket", "/tmp/socket" + control), Map.of()),
                    label + " socket path");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LaunchConfiguration.resolve(
                            List.of(), Map.of(LaunchConfiguration.SOCKET_ENV, "socket" + control)),
                    label + " environment socket name");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LaunchConfiguration.resolve(
                            List.of(), Map.of(LaunchConfiguration.SOCKET_PATH_ENV, "/tmp/socket" + control)),
                    label + " environment socket path");
        }
    }

    @Test
    void startupKeepsApostrophesInClientRoutes() {
        LaunchConfiguration launch =
                LaunchConfiguration.resolve(List.of("--tmux", "/tmp/tmux's", "--socket-name", "socket's"), Map.of());

        assertEquals("/tmp/tmux's", launch.config().binary());
        assertEquals(ServerEndpoint.namedSocket("socket's"), launch.config().endpoint());
    }

    @Test
    void startupRejectsControlCharactersReportedInAResolvedSocket() {
        LaunchConfiguration launch = LaunchConfiguration.resolve(List.of("--socket", "/tmp/configured.sock"), Map.of());
        TmuxTransport reporting = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (VersionProbes.is(request)) {
                    return VersionProbes.reply(request, "3.6");
                }
                return new CommandResult(0, List.of("/tmp/reported\nsocket"), List.of());
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(launch.config(), reporting)) {
            assertThrows(IllegalArgumentException.class, () -> launch.profile(server));
        }
    }

    private static SocketProfile profile(LaunchConfiguration launch) {
        // Startup refuses a reported path that names no file, so the double has to name one.
        Path socket = reportableSocket();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (VersionProbes.is(request)) {
                    return VersionProbes.reply(request, "3.6");
                }
                List<String> command = request.commands().getLast();
                if (command.getFirst().equals("display-message")) {
                    return new CommandResult(0, List.of(socket.toString()), List.of());
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

    private static Path reportableSocket() {
        try {
            Path socket = Files.createTempFile("libtmux-mcp-", ".sock");
            socket.toFile().deleteOnExit();
            return socket;
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
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
