package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class PaneCommandFrameTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    @Test
    void resolvesConfiguredAndSearchedExecutables(@TempDir Path temporary) throws Exception {
        Path working = Files.createDirectory(temporary.resolve("working"));
        Path direct =
                executable(Files.createDirectories(working.resolve("tools")).resolve("tmux"));
        Path relativePath =
                executable(Files.createDirectories(working.resolve("bin")).resolve("tmux"));
        Path emptyPath = executable(working.resolve("tmux"));
        Path absolute = executable(temporary.resolve("absolute-tmux"));

        assertEquals(
                direct.toRealPath().toString(), PaneCommandFrame.resolveExecutable("./tools/tmux", Map.of(), working));
        assertEquals(
                relativePath.toRealPath().toString(),
                PaneCommandFrame.resolveExecutable("tmux", Map.of("PATH", "bin"), working));
        assertEquals(
                emptyPath.toRealPath().toString(),
                PaneCommandFrame.resolveExecutable("tmux", Map.of("PATH", ":elsewhere"), working));
        assertEquals(
                absolute.toRealPath().toString(),
                PaneCommandFrame.resolveExecutable(absolute.toString(), Map.of(), working));
    }

    @Test
    void rejectsExecutableFallbacks(@TempDir Path temporary) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        Path ordinary = Files.writeString(temporary.resolve("ordinary"), "not executable");

        assertThrows(
                IllegalArgumentException.class, () -> PaneCommandFrame.resolveExecutable("tmux", Map.of(), temporary));
        assertThrows(
                IllegalArgumentException.class,
                () -> PaneCommandFrame.resolveExecutable("tmux", Map.of("PATH", "missing"), temporary));
        assertThrows(
                IllegalArgumentException.class,
                () -> PaneCommandFrame.resolveExecutable("./missing", Map.of(), temporary));
        assertThrows(
                IllegalArgumentException.class,
                () -> PaneCommandFrame.resolveExecutable(directory.toString(), Map.of(), temporary));
        assertThrows(
                IllegalArgumentException.class,
                () -> PaneCommandFrame.resolveExecutable(ordinary.toString(), Map.of(), temporary));
    }

    @Test
    void everyEndpointUsesTheAuthoritativeSocket(@TempDir Path temporary) throws Exception {
        Path executable = executable(temporary.resolve("tmux"));
        AtomicInteger queries = new AtomicInteger();
        List<ServerEndpoint> endpoints = List.of(
                ServerEndpoint.defaultSocket(),
                ServerEndpoint.namedSocket("chosen"),
                ServerEndpoint.socketPath(Path.of("/tmp/configured.sock")));
        for (ServerEndpoint endpoint : endpoints) {
            ServerConfig config = config(executable, endpoint);
            PaneCommandFrame frame = PaneCommandFrame.resolve(config, Optional.empty(), Map.of(), temporary, () -> {
                queries.incrementAndGet();
                return result("/tmp/authoritative.sock");
            });
            assertEquals(List.of(executable.toRealPath().toString(), "-S", "/tmp/authoritative.sock"), frame.client());
        }
        assertEquals(3, queries.get());
    }

    @Test
    void aRetainedSocketAvoidsDiscoveryWhileBlankDoesNot() {
        AtomicInteger queries = new AtomicInteger();
        String retained = PaneCommandFrame.resolveSocket(Optional.of("/tmp/retained.sock"), () -> {
            throw new AssertionError("a retained socket must avoid discovery");
        });
        String discovered = PaneCommandFrame.resolveSocket(Optional.of(""), () -> {
            queries.incrementAndGet();
            return result("/tmp/discovered.sock");
        });

        assertEquals("/tmp/retained.sock", retained);
        assertEquals("/tmp/discovered.sock", discovered);
        assertEquals(1, queries.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedSockets")
    void malformedSocketRoutesFailClosed(String label, Optional<String> supplied, CommandResult result) {
        assertThrows(
                IllegalArgumentException.class, () -> PaneCommandFrame.resolveSocket(supplied, () -> result), label);
    }

    @ParameterizedTest
    @MethodSource("routeKinds")
    void framedCommandsStayOnTheResolvedEndpoint(String kind, @TempDir Path temporary) throws Exception {
        Path workingDirectory = Path.of("").toAbsolutePath();
        String realTmux = PaneCommandFrame.resolveExecutable(TMUX, System.getenv(), workingDirectory);
        Path intendedRoot = Files.createDirectory(temporary.resolve("intended-root"));
        Path decoyRoot = Files.createDirectory(temporary.resolve("decoy-root"));
        Path intendedSocket = temporary.resolve("intended.sock");
        Path wrapper = temporary.resolve("bin").resolve("tmux-wrapper");
        Files.createDirectories(wrapper.getParent());

        String socketName = "frame-" + temporary.getFileName();
        ServerEndpoint endpoint =
                switch (kind) {
                    case "default" -> ServerEndpoint.defaultSocket();
                    case "named" -> ServerEndpoint.namedSocket(socketName);
                    case "path" -> ServerEndpoint.socketPath(intendedSocket);
                    default -> throw new AssertionError(kind);
                };
        writeWrapper(wrapper, kind, realTmux, intendedRoot, decoyRoot, intendedSocket);
        String configured = kind.equals("default")
                ? workingDirectory.relativize(wrapper.toAbsolutePath()).toString()
                : wrapper.toString();
        Path configFile = Files.writeString(temporary.resolve("tmux.conf"), "");
        ServerConfig config = ServerConfig.builder()
                .binary(configured)
                .endpoint(endpoint)
                .configFile(configFile)
                .build();
        AtomicReference<String> payload = new AtomicReference<>("");

        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = recording(processes, payload);
            try (Server server = Server.using(config, recording)) {
                try {
                    CommandResult created = server.cmd("new-session", "-d", "-s", "route", "/bin/dash");
                    assertTrue(created.succeeded(), created.stderr().toString());
                    String socket = server.cmd("display-message", "-p", "#{socket_path}")
                            .stdout()
                            .getFirst();

                    var pane = server.panes().getFirst();
                    Path changed = Files.createDirectory(temporary.resolve("changed"));
                    Path panePath = Files.createDirectory(temporary.resolve("pane-path"));
                    Path ready = temporary.resolve("pane-ready");
                    pane.sendLine("LIBTMUX_FRAME_PANE=pane; export LIBTMUX_FRAME_PANE; unset TMUX; PATH="
                            + Shell.quote(panePath.toString())
                            + "; TMUX_TMPDIR="
                            + Shell.quote(decoyRoot.toString())
                            + "; export PATH TMUX_TMPDIR; cd "
                            + Shell.quote(changed.toString())
                            + "; : > "
                            + Shell.quote(ready.toString()));
                    assertTrue(await(() -> Files.exists(ready)), "the pane-route setup did not finish");

                    RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(
                            server,
                            "pane_id",
                            pane.id().value(),
                            "command",
                            "printf 'route-" + kind + "\\n'; exit 9",
                            "timeout",
                            5));

                    assertEquals("SIGNALLED", ran.outcome());
                    assertEquals(9, ran.exitStatus());
                    assertEquals(List.of("route-" + kind), ran.output());
                    assertTrue(ran.framed());
                    assertEquals(
                            3, occurrences(payload.get(), wrapper.toRealPath().toString()), payload.get());
                    assertEquals(3, occurrences(payload.get(), socket), payload.get());
                } finally {
                    if (server.isAlive()) {
                        server.killServer();
                    }
                }
            }
        }
    }

    private static Stream<String> routeKinds() {
        return Stream.of("default", "named", "path");
    }

    private static Stream<Arguments> malformedSockets() {
        return Stream.of(
                Arguments.of("nonzero", Optional.empty(), new CommandResult(1, List.of(), List.of("no server"))),
                Arguments.of("zero lines", Optional.empty(), new CommandResult(0, List.of(), List.of())),
                Arguments.of(
                        "multiple lines",
                        Optional.empty(),
                        new CommandResult(0, List.of("/tmp/one", "/tmp/two"), List.of())),
                Arguments.of("blank", Optional.empty(), new CommandResult(0, List.of(" "), List.of())),
                Arguments.of("relative", Optional.empty(), new CommandResult(0, List.of("relative.sock"), List.of())),
                Arguments.of(
                        "relative retained",
                        Optional.of("relative.sock"),
                        new CommandResult(0, List.of("/tmp/unused.sock"), List.of())));
    }

    private static Path executable(Path path) throws IOException {
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        assertTrue(path.toFile().setExecutable(true), "could not make test executable");
        return path;
    }

    private static ServerConfig config(Path executable, ServerEndpoint endpoint) {
        return ServerConfig.builder()
                .binary(executable.toString())
                .endpoint(endpoint)
                .build();
    }

    private static CommandResult result(String socket) {
        return new CommandResult(0, List.of(socket), List.of());
    }

    private static void writeWrapper(
            Path wrapper, String kind, String realTmux, Path intendedRoot, Path decoyRoot, Path socket)
            throws IOException {
        String javaRoute =
                switch (kind) {
                    case "default" ->
                        "exec " + Shell.quote(realTmux) + " -S " + Shell.quote(socket.toString()) + " \"$@\"";
                    case "named" ->
                        "TMUX_TMPDIR=" + Shell.quote(intendedRoot.toString()) + "; export TMUX_TMPDIR; exec "
                                + Shell.quote(realTmux) + " \"$@\"";
                    case "path" -> "exec " + Shell.quote(realTmux) + " \"$@\"";
                    default -> throw new AssertionError(kind);
                };
        String script = """
                #!/bin/sh
                set -eu
                if [ "${LIBTMUX_FRAME_PANE-}" = pane ]; then
                  for argument in "$@"; do
                    if [ "$argument" = -S ]; then exec %s "$@"; fi
                  done
                  TMUX_TMPDIR=%s; export TMUX_TMPDIR
                  exec %s "$@"
                fi
                %s
                """.formatted(
                        Shell.quote(realTmux), Shell.quote(decoyRoot.toString()), Shell.quote(realTmux), javaRoute);
        Files.writeString(wrapper, script);
        assertTrue(wrapper.toFile().setExecutable(true), "could not make route wrapper executable");
    }

    private static TmuxTransport recording(ProcessTransport processes, AtomicReference<String> payload) {
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                request.commands().stream()
                        .flatMap(List::stream)
                        .filter(argument -> argument.contains("display-message") && argument.contains("ch_lt"))
                        .forEach(payload::set);
                return processes.execute(request);
            }

            @Override
            public void close() {}
        };
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
