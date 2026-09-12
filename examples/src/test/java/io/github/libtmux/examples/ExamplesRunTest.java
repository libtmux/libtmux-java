package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.PaneOutput;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every example, run against a real tmux.
 *
 * <p>Examples rot silently. They are the part of a project nobody compiles and everybody reads
 * first, so an API change breaks them without breaking anything that would say so. These call the
 * same method {@code main} calls, which is why each example has one.
 */
@ExtendWith(TmuxExtension.class)
final class ExamplesRunTest {

    private static final String ARENA_EVIDENCE_PREFIX = "LIBTMUX_ARENA_EVIDENCE=";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void buildingAWorkspaceLeavesOneBehind(Server server, TmuxSocketPath socket) {
        String reported = BuildAWorkspace.run(socket.path());

        assertTrue(reported.startsWith("session work has "), reported);
        assertTrue(server.hasSession("work"), "the example is supposed to leave a session running");
    }

    @Test
    void arenaAliasesAndAnEmptyDescriptorKeepTheSocketArgumentPath(Server server, TmuxSocketPath socket) {
        Map<String, String> aliases = Map.of(
                "LIBTMUX_SOCKET_PATH", socket.path().toString(),
                "LIBTMUX_TMUX_BIN", server.config().binary());

        assertTrue(BuildAWorkspace.arenaConfig(aliases).isEmpty());
        assertTrue(BuildAWorkspace.arenaConfig(with(aliases, "LIBTMUX_ARENA_DESCRIPTOR", ""))
                .isEmpty());
        assertTrue(BuildAWorkspace.run(socket.path()).startsWith("session work has "));
    }

    @Test
    void arenaRejectsActivatedIncompleteAndMismatchedContracts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildAWorkspace.arenaConfig(Map.of("LIBTMUX_ARENA_DESCRIPTOR", "arena")));
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildAWorkspace.arenaConfig(Map.of(
                        "LIBTMUX_ARENA_DESCRIPTOR", "arena",
                        "LIBTMUX_ARENA_ARTIFACT", "java-build-a-workspace",
                        "LIBTMUX_SOCKET_PATH", "",
                        "LIBTMUX_TMUX_BIN", "not-a-tmux-binary")));
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildAWorkspace.arenaConfig(Map.of(
                        "LIBTMUX_ARENA_DESCRIPTOR", "arena",
                        "LIBTMUX_ARENA_ARTIFACT", "other-artifact",
                        "LIBTMUX_SOCKET_PATH", "/tmp/no-server",
                        "LIBTMUX_TMUX_BIN", "not-a-tmux-binary")));
    }

    @Test
    void arenaConfigPinsTheRequestedBinaryAndSocket(TmuxSocketPath socket) {
        ServerConfig config = BuildAWorkspace.arenaConfig(Map.of(
                        "LIBTMUX_ARENA_DESCRIPTOR", "arena",
                        "LIBTMUX_ARENA_ARTIFACT", "java-build-a-workspace",
                        "LIBTMUX_SOCKET_PATH", socket.path().toString(),
                        "LIBTMUX_TMUX_BIN", "requested-tmux"))
                .orElseThrow();

        assertEquals("requested-tmux", config.binary());
        assertEquals(
                List.of("-S", socket.path().toAbsolutePath().normalize().toString()),
                config.endpoint().flags());
    }

    @Test
    void arenaMainEmitsOneValidatedEvidenceRecordWithoutStoppingExternalServer(Server server, TmuxSocketPath socket)
            throws Exception {
        String challenge = "quote\" slash\\";
        server.globalOptions().set("@libtmux_arena_challenge", challenge);
        Path output = socket.path().resolveSibling("arena-main-output");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                BuildAWorkspace.class.getName());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        builder.environment().remove("TMUX");
        builder.environment().remove("TMUX_PANE");
        builder.environment().putAll(arenaEnvironment(server, socket));

        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
        }
        List<String> lines = Files.readAllLines(output);

        assertTrue(finished, () -> "the arena main did not finish; it said " + lines);
        assertEquals(0, process.exitValue(), () -> "the arena main failed; it said " + lines);
        List<String> records = lines.stream()
                .filter(line -> line.startsWith(ARENA_EVIDENCE_PREFIX))
                .toList();
        assertEquals(1, records.size(), () -> "expected one arena evidence record; main said " + lines);
        JsonNode evidence = JSON.readTree(records.getFirst().substring(ARENA_EVIDENCE_PREFIX.length()));

        assertEquals(arenaEvidence(challenge, server, socket), evidence);
        assertTrue(server.hasSession("work"), "closing the arena client must not stop its daemon");
    }

    @Test
    void arenaJsonRejectsUnpairedSurrogatesAndPreservesUnicode() throws Exception {
        String controls = "\u0000\b\f\n\r\t\u001f\"\\";
        String supplementary = "\ud83d\ude03";

        assertEquals(controls, JSON.readValue(ArenaSupport.jsonString(controls), String.class));
        assertEquals(supplementary, JSON.readValue(ArenaSupport.jsonString(supplementary), String.class));
        assertThrows(IllegalArgumentException.class, () -> ArenaSupport.jsonString("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> ArenaSupport.jsonString("\udc00"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arenaArtifacts")
    void arenaRefusesEveryArtifactWithoutTouchingAnyServerWhenTheSocketIsMissing(
            String artifact, Class<?> exampleClass, TmuxSocketPath socket) throws Exception {
        Path output = socket.path().resolveSibling("arena-missing-socket-" + artifact);
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                exampleClass.getName());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        builder.environment().remove("TMUX");
        builder.environment().remove("TMUX_PANE");
        builder.environment().put("LIBTMUX_ARENA_DESCRIPTOR", "arena");
        builder.environment().put("LIBTMUX_ARENA_ARTIFACT", artifact);
        builder.environment().put("LIBTMUX_TMUX_BIN", "tmux");
        // LIBTMUX_SOCKET_PATH is deliberately absent.

        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
        }
        List<String> lines = Files.readAllLines(output);

        assertTrue(finished, () -> "the arena main did not finish; it said " + lines);
        assertNotEquals(
                0, process.exitValue(), () -> "expected a nonzero exit for a missing socket path; it said " + lines);
        assertTrue(
                lines.stream().noneMatch(line -> line.startsWith(ARENA_EVIDENCE_PREFIX)),
                () -> "expected no evidence line; main said " + lines);
    }

    private static Stream<Arguments> arenaArtifacts() {
        return Stream.of(
                Arguments.of("java-build-a-workspace", BuildAWorkspace.class),
                Arguments.of("java-find-panes-running", FindPanesRunning.class),
                Arguments.of("java-serve-tmux-over-mcp", ServeTmuxOverMcp.class),
                Arguments.of("java-watch-pane-output", WatchPaneOutput.class),
                Arguments.of("java-watch-what-changes", WatchWhatChanges.class));
    }

    @Test
    void findingPanesSelectsOnWhatIsRunning(Server server, TmuxSocketPath socket) throws InterruptedException {
        // The fixture's pane runs a shell, so the shell's own name is the one thing certain to match —
        // once it has settled. While the shell starts, tmux reports whatever its startup files are
        // running, locale for one, and a name read then matches nothing a moment later.
        Pane shell = server.panes().get(0);
        String[] previous = {shell.currentCommand()};
        shell.await(
                fresh -> {
                    boolean settled = fresh.currentCommand().equals(previous[0]);
                    previous[0] = fresh.currentCommand();
                    return settled;
                },
                Duration.ofSeconds(10));
        String running = previous[0];

        List<Pane> found = FindPanesRunning.run(socket.path(), running);

        assertFalse(found.isEmpty(), "nothing matched '" + running + "'");
        assertTrue(found.stream().allMatch(pane -> pane.currentCommand().startsWith(running)));
        assertEquals(List.of(), FindPanesRunning.run(socket.path(), "no-such-command-anywhere"));
    }

    @Test
    void watchingAPaneSeesWhatItPrints(Server server, TmuxSocketPath socket) {
        List<PaneOutput> seen = WatchPaneOutput.run(socket.path(), Duration.ofSeconds(30), output -> {});

        assertFalse(seen.isEmpty(), "attaching is what makes tmux push output, and none arrived");
        assertTrue(
                server.hasSession("watch-pane-output"),
                "the example must seed its own session rather than reuse whichever session came first");
    }

    @Test
    void servingOverMcpReportsTheFixedToolSurface(TmuxSocketPath socket) {
        List<String> tools = ServeTmuxOverMcp.run(socket.path());

        // The catalog holds 45. Teardown is enabled by default only for a daemon this process
        // created, and the fixture's server already exists, so its four tools are not offered.
        assertEquals(41, tools.size(), tools.toString());
        assertTrue(tools.contains("capture_pane"), tools.toString());
        assertFalse(tools.contains("kill_session"), "teardown reached a server the example did not start: " + tools);
        assertEquals(tools.stream().sorted().toList(), tools, "the example reports a stable order");
    }

    @Test
    void watchingAServerIsToldWhenAWindowAppears(Server server, TmuxSocketPath socket) {
        List<ControlEvent> seen = WatchWhatChanges.run(socket.path(), Duration.ofSeconds(30), event -> {});

        assertTrue(
                WatchWhatChanges.sawTheNewWindow(seen),
                "tmux compares a watched format itself and reports the difference: " + seen);
        assertTrue(
                server.hasSession("watch-what-changes"),
                "the example must seed its own session rather than reuse whichever session came first");
    }

    private static Map<String, String> arenaEnvironment(Server server, TmuxSocketPath socket) {
        return Map.of(
                "LIBTMUX_ARENA_DESCRIPTOR",
                "arena",
                "LIBTMUX_ARENA_ARTIFACT",
                "java-build-a-workspace",
                "LIBTMUX_SOCKET_PATH",
                socket.path().toString(),
                "LIBTMUX_TMUX_BIN",
                server.config().binary());
    }

    private static Map<String, String> with(Map<String, String> values, String name, String value) {
        var copy = new java.util.HashMap<>(values);
        copy.put(name, value);
        return copy;
    }

    private static JsonNode arenaEvidence(String challenge, Server server, TmuxSocketPath socket) {
        return JSON.createObjectNode()
                .put("artifact", "java-build-a-workspace")
                .put("challenge", challenge)
                .put("schema", 1)
                .put("server_pid", Integer.parseInt(server.expand("#{pid}")))
                .put("socket_path", socket.path().toAbsolutePath().normalize().toString());
    }
}
