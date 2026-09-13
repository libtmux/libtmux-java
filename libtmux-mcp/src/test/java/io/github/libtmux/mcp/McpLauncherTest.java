package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.NamedServerFixture;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher an MCP client starts, started that way. A wrong endpoint passes every in-process
 * test in this module and still serves a model the wrong machine's tmux.
 */
@ExtendWith(TmuxExtension.class)
final class McpLauncherTest {

    /**
     * How long a launcher gets to exit once its session has ended.
     *
     * <p>These cases prove the launcher does not wait for an end of input that never comes, so any
     * finite bound proves it; stdin is never closed. Five seconds was a claim about how fast a JVM
     * starts on a loaded machine, and it failed on the first, coldest run on a clean checkout as
     * often as on a changed one.
     */
    private static final long EXIT_BUDGET_SECONDS = 60;

    /** Named explicitly: a child resolving tmux from PATH would answer about a different build. */
    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    /** Longer than any single call needs, short enough that a hung launcher fails as itself. */
    private static final int PATIENCE_SECONDS = 60;

    @Test
    @Timeout(PATIENCE_SECONDS)
    void layoutWireRejectsMalformedSyntaxBeforeLookingUpAWindow(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("select_layout")
                    .arguments(Map.of("window_id", "@999999", "layout", "32d2,80x24,0,0{}"))
                    .build());

            assertTrue(Boolean.TRUE.equals(result.isError()), textOf(result));
            assertTrue(textOf(result).contains("not a tmux layout"), textOf(result));
            assertFalse(textOf(result).contains("no window"), textOf(result));
        }
    }

    @Test
    @Timeout(PATIENCE_SECONDS)
    void layoutWireAcceptsNativeAbbreviationsAndSavedTrees(Server server, TmuxSocketPath socket) {
        var window = server.windows().getFirst();
        window.split();
        String saved = window.refresh().layout();
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();
            McpSchema.CallToolResult abbreviated = client.callTool(McpSchema.CallToolRequest.builder("select_layout")
                    .arguments(Map.of("window_id", window.id().value(), "layout", "even-h"))
                    .build());
            McpSchema.CallToolResult restored = client.callTool(McpSchema.CallToolRequest.builder("select_layout")
                    .arguments(Map.of("window_id", window.id().value(), "layout", saved))
                    .build());
            assertAll(
                    () -> assertFalse(Boolean.TRUE.equals(abbreviated.isError()), textOf(abbreviated)),
                    () -> assertFalse(Boolean.TRUE.equals(restored.isError()), textOf(restored)));
            assertEquals(
                    "EVEN_HORIZONTAL",
                    Answers.JSON
                            .valueToTree(abbreviated.structuredContent())
                            .path("what")
                            .asText());
            assertEquals(
                    saved,
                    Answers.JSON
                            .valueToTree(restored.structuredContent())
                            .path("what")
                            .asText());
            assertEquals(saved, window.refresh().layout());
            assertEquals(2, window.refresh().panes().size());
        }
    }

    @Test
    @Timeout(PATIENCE_SECONDS)
    void layoutWirePreservesKeeperAcrossNativeRefusals(Server server, TmuxSocketPath socket) {
        var window = server.windows().getFirst();
        window.split();
        long pid = server.snapshot().serverPid().orElseThrow();
        var sessionId = window.session().id();
        boolean mirrored = server.version().atLeast(new TmuxVersion(3, 5, ""));
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();
            for (String layout : List.of(
                    "not-a-layout",
                    "-E",
                    "32d2,80x24,0,0{}",
                    "4a17,80x24,0,0{39x24,0,0,0,40x24,40,0[]}",
                    "ffff,80x24,0,0,0",
                    "even",
                    "79f5,80x24,0,0{39x23,0,0,0,40x24,40,0,1}",
                    mirrored ? "main-h" : "main-horizontal-mirrored")) {
                String before = window.refresh().layout();
                McpSchema.CallToolResult refused = client.callTool(McpSchema.CallToolRequest.builder("select_layout")
                        .arguments(Map.of("window_id", window.id().value(), "layout", layout))
                        .build());
                assertTrue(Boolean.TRUE.equals(refused.isError()), layout + ": " + textOf(refused));
                assertFalse(textOf(refused).isBlank());
                assertEquals(before, window.refresh().layout(), layout);
            }
            for (String layout : List.of(
                    "t",
                    "even-h",
                    "EVEN_HORIZONTAL",
                    "even_horizontal",
                    "main-horizontal",
                    "8A08,1x1,0,0{39x24,0,0,0,40x24,40,0,1}",
                    mirrored ? "main-horizontal-mirrored" : "main-h")) {
                McpSchema.CallToolResult accepted = client.callTool(McpSchema.CallToolRequest.builder("select_layout")
                        .arguments(Map.of("window_id", window.id().value(), "layout", layout))
                        .build());
                assertFalse(Boolean.TRUE.equals(accepted.isError()), layout + ": " + textOf(accepted));
            }
            McpSchema.CallToolResult missing = client.callTool(McpSchema.CallToolRequest.builder("select_layout")
                    .arguments(Map.of("window_id", "@999999", "layout", "tiled"))
                    .build());
            assertTrue(Boolean.TRUE.equals(missing.isError()), textOf(missing));
            assertTrue(textOf(missing).contains("no window @999999"), textOf(missing));
        }
        assertEquals(pid, server.snapshot().serverPid().orElseThrow());
        assertEquals(1, server.sessions().size());
        assertEquals(sessionId, server.sessions().getFirst().id());
        assertEquals(2, window.refresh().panes().size());
    }

    @Test
    void theRemovedWatchFlagDoesNotLeaveTheLauncherAlive(Server server, TmuxSocketPath socket) throws Exception {
        Process launcher =
                rawLauncher(socket.path(), ProcessBuilder.Redirect.DISCARD, ProcessBuilder.Redirect.PIPE, "--watch");
        try {
            assertTrue(
                    launcher.waitFor(5, TimeUnit.SECONDS), "the rejected launch stayed alive on its transport threads");
            assertTrue(launcher.exitValue() != 0, "the removed watch flag reported success");
            String diagnostic = new String(launcher.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(
                    diagnostic.contains("unknown argument '--watch'"),
                    "the launcher failed for the wrong reason: " + diagnostic);
        } finally {
            stop(launcher);
        }
    }

    /** Protocol failure ends the session even when the client forgets to close its stdin pipe. */
    @Test
    void malformedInputDoesNotLeaveTheLauncherWaitingForEndOfInput(Server server, TmuxSocketPath socket)
            throws Exception {
        Process launcher = rawLauncher(socket.path(), ProcessBuilder.Redirect.DISCARD);
        try {
            launcher.getOutputStream().write("{not-json}\n".getBytes(StandardCharsets.UTF_8));
            launcher.getOutputStream().flush();

            assertTrue(
                    launcher.waitFor(EXIT_BUDGET_SECONDS, TimeUnit.SECONDS),
                    "the protocol session ended, but the launcher was still waiting for stdin EOF");
        } finally {
            stop(launcher);
        }
    }

    /** Broken stdout is also a disconnect, even if the client leaves stdin open. */
    @Test
    void brokenOutputDoesNotLeaveTheLauncherWaitingForEndOfInput(Server server, TmuxSocketPath socket)
            throws Exception {
        Process launcher = rawLauncher(socket.path(), ProcessBuilder.Redirect.PIPE);
        try {
            launcher.getInputStream().close();
            String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"" + ProtocolVersions.MCP_2025_11_25
                    + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}\n";
            launcher.getOutputStream().write(initialize.getBytes(StandardCharsets.UTF_8));
            launcher.getOutputStream().flush();

            assertTrue(
                    launcher.waitFor(EXIT_BUDGET_SECONDS, TimeUnit.SECONDS),
                    "stdout failed, but the launcher was still waiting for stdin EOF");
            assertEquals(0, launcher.exitValue());
        } finally {
            stop(launcher);
        }
    }

    @Test
    @Timeout(PATIENCE_SECONDS)
    void aLaunchedServerAnswersAboutTheSocketItWasGiven(Server server, TmuxSocketPath socket) {
        server.sessions().get(0).newWindow("editor");

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            String listed = textOf(client.callTool(
                    McpSchema.CallToolRequest.builder("list_sessions").build()));

            assertTrue(listed.contains("libtmux"), "the launcher did not find the fixture's session: " + listed);
            assertTrue(listed.contains("editor"), "the launcher answered about a different server: " + listed);
        }
    }

    @Test
    @Timeout(PATIENCE_SECONDS)
    void everyToolTheReadmeNamesIsOffered(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            List<String> offered = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();

            assertTrue(
                    offered.containsAll(List.of(
                            "list_sessions", "list_panes", "capture_pane", "run_shell_command", "create_window")),
                    offered.toString());
        }
    }

    /**
     * The surface a model meets before it calls anything: what this server is for, and which tool to
     * reach for. A description drifting from what the tools do sends every model the same wrong way.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void theServerTellsAModelHowToUseItBeforeItCallsAnything(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            McpSchema.InitializeResult initialized = client.initialize();
            String instructions = String.valueOf(initialized.instructions());

            assertTrue(instructions.contains("WAIT, DO NOT POLL"), instructions);
            assertTrue(instructions.contains("get_server_info"), "a model has to be told how to identify the server");
            assertTrue(instructions.contains("Do NOT use them for browser tabs"), "anti-triggers must be stated");
            assertTrue(instructions.contains("Tool filtering"), "the interface boundary must be stated");
        }
    }

    /** Capabilities are the only resource; no second prompt authority is advertised. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void theCapabilityReportIsTheOnlyResource(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            List<String> resources = client.listResources().resources().stream()
                    .map(McpSchema.Resource::uri)
                    .toList();
            assertEquals(List.of(Resources.CAPABILITIES_URI), resources);
        }
    }

    /** What a client decides to confirm with a person on comes from these, so they have to arrive. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void everyToolArrivesWithItsRiskDeclared(Server server, TmuxSocketPath socket) throws Exception {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            List<McpSchema.Tool> tools = client.listTools().tools();
            McpSchema.ReadResourceResult resource =
                    client.readResource(McpSchema.ReadResourceRequest.builder(Resources.CAPABILITIES_URI)
                            .build());
            McpSchema.TextResourceContents contents =
                    (McpSchema.TextResourceContents) resource.contents().getFirst();
            @SuppressWarnings("unchecked")
            Map<String, Object> report = new ObjectMapper().readValue(contents.text(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    java.util.Objects.requireNonNull(report.get("tools"), "capability rows");

            for (McpSchema.Tool tool : tools) {
                assertTrue(tool.annotations() != null, tool.name() + " arrived with no annotations");
                assertEquals(
                        java.util.Set.of(ToolSpec.CAPABILITY_META_KEY),
                        tool.meta().keySet(),
                        tool.name() + " arrived without its capability row");
                Map<String, Object> row = rows.stream()
                        .filter(candidate -> tool.name().equals(candidate.get("name")))
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        row,
                        tool.meta().get(ToolSpec.CAPABILITY_META_KEY),
                        tool.name() + " metadata differs from tmux://capabilities");
            }
            McpSchema.Tool reading = named(client, "capture_pane");
            McpSchema.Tool running = named(client, "run_shell_command");

            assertEquals(
                    false,
                    reading.annotations().readOnlyHint(),
                    "unknown configuration provenance requires conservative whole-call annotations");
            assertEquals(false, running.annotations().readOnlyHint(), "running a command does");
            assertEquals(true, running.annotations().destructiveHint(), "a shell command may delete data");
        }
    }

    /** A toolset selection removes tools rather than refusing them, and this reaches the client. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void anInspectOnlyLauncherDoesNotOfferToolsThatChangeThings(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client =
                launch("--socket", socket.path().toString(), Map.of(ToolSurface.TOOLSETS_ENV, "inspect"))) {
            client.initialize();

            List<String> offered = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();

            assertTrue(offered.contains("capture_pane"), offered.toString());
            assertFalse(offered.contains("run_shell_command"), "an inspect-only server must not offer execution");
            assertFalse(offered.contains("kill_session"), offered.toString());
        }
    }

    /**
     * The flagship path over a real wire: a command sent, waited for, and its exit status handed back
     * as a number rather than something to infer from the screen.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aCommandRunsAndItsExitStatusComesBack(Server server, TmuxSocketPath socket) {
        String pane = server.sessions()
                .get(0)
                .newWindow(window -> window.named("runner").running("/bin/sh"))
                .panes()
                .get(0)
                .id()
                .value();

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            McpSchema.CallToolResult ran = client.callTool(McpSchema.CallToolRequest.builder("run_shell_command")
                    .arguments(Map.of("pane_id", pane, "command", "echo over-the-wire; exit 7", "timeout", 30))
                    .build());

            String answer = textOf(ran);
            assertTrue(answer.contains("\"exit_status\":7"), answer);
            assertTrue(answer.contains("over-the-wire"), answer);
            assertFalse(answer.contains("wait-for"), "no plumbing may reach the model: " + answer);
            assertTrue(ran.structuredContent() != null, "a client that parses gets the object too");
        }
    }

    /**
     * A model that sends one value where a list is wanted meant the one value.
     *
     * <p>Over the wire, not in process: the server validates arguments against a tool's own schema
     * before the tool sees them, so a reader that copes with a bare string proves nothing unless the
     * schema says a bare string is allowed. Tested in process only, this passed while every real
     * client was refused.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void oneValueIsAcceptedWhereAListIsWanted(Server server, TmuxSocketPath socket) {
        String pane = server.panes().get(0).id().value();

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            McpSchema.CallToolResult keys = client.callTool(McpSchema.CallToolRequest.builder("send_keys")
                    .arguments(Map.of("pane_id", pane, "keys", "q"))
                    .build());
            McpSchema.CallToolResult waited = client.callTool(McpSchema.CallToolRequest.builder("wait_for_text")
                    .arguments(Map.of("pane_id", pane, "patterns", "never-appears-here", "timeout", 1))
                    .build());

            assertEquals(false, keys.isError(), textOf(keys));
            assertEquals(false, waited.isError(), textOf(waited));
        }
    }

    private static McpSchema.Tool named(McpSyncClient client, String name) {
        return client.listTools().tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " was not offered"));
    }

    /**
     * A model can act on "no pane %9" and can do nothing with a stack trace that never reaches it,
     * so a refusal has to arrive as a tool error rather than as a dead subprocess.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aTargetThatIsNotThereComesBackAsAToolErrorAndTheServerLivesOn(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            McpSchema.CallToolResult refused = client.callTool(McpSchema.CallToolRequest.builder("capture_pane")
                    .arguments(Map.of("pane_id", "%999"))
                    .build());

            assertEquals(true, refused.isError(), "a missing pane was reported as success");
            assertTrue(textOf(refused).contains("%999"), textOf(refused));

            // The same client keeps working, which is what separates a tool error from a crash.
            assertFalse(
                    textOf(client.callTool(McpSchema.CallToolRequest.builder("list_sessions")
                                    .build()))
                            .isEmpty(),
                    "the launcher died on a bad target instead of reporting it");
        }
    }

    /** A name resolves under {@code TMUX_TMPDIR}, so it is stated rather than left to be inherited. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aServerAddressedByNameIsFoundToo(@TempDir Path directory) throws Exception {
        String name = "ltj-mcp-" + ProcessHandle.current().pid();

        try (Server named = openNamed(name, directory)) {
            named.newSession("by-name");
            try (NamedServerFixture owned = NamedServerFixture.own(named, name, Path.of(tmuxTmpDir()))) {
                assertEquals(name, owned.socket().getFileName().toString());

                try (McpSyncClient client = launch("--socket-name", name, Map.of("TMUX_TMPDIR", tmuxTmpDir()))) {
                    client.initialize();

                    String listed = textOf(client.callTool(
                            McpSchema.CallToolRequest.builder("list_sessions").build()));

                    assertTrue(listed.contains("by-name"), "the launcher did not find the named server: " + listed);
                }
            }
        }
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the tool answered with no text at all"));
    }

    private static McpSyncClient launch(Path socket, String... extra) {
        return launch("--socket", socket.toString(), Map.of(), extra);
    }

    /**
     * The SDK adds its environment to an inherited one rather than replacing it, so what keeps a
     * child off the suite's own server is the build having removed {@code TMUX}, asserted here.
     */
    private static McpSyncClient launch(
            String endpoint, String value, Map<String, String> environment, String... extra) {
        assertNull(System.getenv("TMUX"), "the suite is running inside tmux, so a child could inherit it");
        assertNull(System.getenv("TMUX_PANE"), "the suite is running inside a pane, so a child could inherit it");

        List<String> args = new java.util.ArrayList<>(List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                Main.class.getName(),
                endpoint,
                value,
                "--tmux",
                TMUX));
        args.addAll(List.of(extra));
        ServerParameters launcher = ServerParameters.builder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args(args)
                .env(environment)
                .build();
        return McpClient.sync(new StdioClientTransport(launcher, new JacksonMcpJsonMapper(new ObjectMapper())))
                .build();
    }

    private static Process rawLauncher(Path socket, ProcessBuilder.Redirect output, String... extra)
            throws IOException {
        return rawLauncher(socket, output, ProcessBuilder.Redirect.DISCARD, extra);
    }

    private static Process rawLauncher(
            Path socket, ProcessBuilder.Redirect output, ProcessBuilder.Redirect error, String... extra)
            throws IOException {
        List<String> args = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                Main.class.getName(),
                "--socket",
                socket.toString(),
                "--tmux",
                TMUX));
        args.addAll(List.of(extra));
        return new ProcessBuilder(args)
                .redirectOutput(output)
                .redirectError(error)
                .start();
    }

    private static void stop(Process launcher) throws InterruptedException, IOException {
        launcher.getOutputStream().close();
        if (!launcher.waitFor(5, TimeUnit.SECONDS)) {
            launcher.destroyForcibly();
            launcher.waitFor(5, TimeUnit.SECONDS);
        }
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

    private static String tmuxTmpDir() {
        String configured = System.getenv("TMUX_TMPDIR");
        assertTrue(configured != null && !configured.isEmpty(), "the build did not quarantine TMUX_TMPDIR");
        return configured;
    }
}
