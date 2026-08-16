package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher an MCP client actually starts, started the way an MCP client starts it.
 *
 * <p>Everything below the protocol is covered by {@link TmuxToolsTest} against a server built in
 * process. That leaves the part no in-process test can reach: whether the flags a README tells
 * people to write reach a tmux server at all, and whether a tool call survives the round trip
 * through JSON-RPC and a pipe. A launcher that parsed {@code --socket} into the wrong endpoint would
 * still pass every other test in this module and serve a model the wrong machine's tmux.
 *
 * <p>The subprocess is a JVM of its own, so it takes the tmux binary explicitly: on a matrix lane
 * the fixture runs a specific build, and a child resolving {@code tmux} from {@code PATH} would
 * quietly answer about a different one.
 */
@ExtendWith(TmuxExtension.class)
final class McpLauncherTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    /** Longer than any single call needs, short enough that a hung launcher fails as itself. */
    private static final int PATIENCE_SECONDS = 60;

    @Test
    @Timeout(PATIENCE_SECONDS)
    void aLaunchedServerAnswersAboutTheSocketItWasGiven(Server server, TmuxSocketPath socket) {
        server.sessions().get(0).newWindow("editor");

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            String listed = textOf(client.callTool(
                    McpSchema.CallToolRequest.builder("tmux_list_sessions").build()));

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
                            "tmux_list_sessions",
                            "tmux_list_panes",
                            "tmux_capture_pane",
                            "tmux_run",
                            "tmux_new_window")),
                    offered.toString());
        }
    }

    /**
     * The filter is the one argument that is a structured document rather than a string, so it is
     * the one that can be mangled between the model and tmux without anything noticing.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aFilterSentAsADocumentNarrowsWhatComesBack(Server server, TmuxSocketPath socket) {
        String running = server.panes().get(0).currentCommand();

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            String matching = textOf(client.callTool(McpSchema.CallToolRequest.builder("tmux_list_panes")
                    .arguments(Map.of("filter", filterOn(running)))
                    .build()));
            String missing = textOf(client.callTool(McpSchema.CallToolRequest.builder("tmux_list_panes")
                    .arguments(Map.of("filter", filterOn("no-such-command-anywhere")))
                    .build()));

            assertTrue(matching.contains("\"id\":\"%"), "the filter excluded the pane that matches it: " + matching);
            assertEquals("[]", missing, "a filter matching nothing must return nothing, not everything");
        }
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

            McpSchema.CallToolResult refused = client.callTool(McpSchema.CallToolRequest.builder("tmux_capture_pane")
                    .arguments(Map.of("pane_id", "%999"))
                    .build());

            assertEquals(true, refused.isError(), "a missing pane was reported as success");
            assertTrue(textOf(refused).contains("%999"), textOf(refused));

            // The same client keeps working, which is what separates a tool error from a crash.
            assertFalse(
                    textOf(client.callTool(McpSchema.CallToolRequest.builder("tmux_list_sessions")
                                    .build()))
                            .isEmpty(),
                    "the launcher died on a bad target instead of reporting it");
        }
    }

    /**
     * A name is not a path: tmux resolves it under {@code TMUX_TMPDIR} when the child execs, so this
     * is the one arm whose answer depends on the environment as well as the arguments. The directory
     * is stated rather than inherited, so what this proves does not depend on the suite's own.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aServerAddressedByNameIsFoundToo(@TempDir Path directory) throws Exception {
        String name = "ltj-mcp-" + ProcessHandle.current().pid();

        try (Server named = openNamed(name, directory)) {
            try {
                named.newSession("by-name");

                try (McpSyncClient client = launch("--socket-name", name, Map.of("TMUX_TMPDIR", tmuxTmpDir()))) {
                    client.initialize();

                    String listed = textOf(client.callTool(McpSchema.CallToolRequest.builder("tmux_list_sessions")
                            .build()));

                    assertTrue(listed.contains("by-name"), "the launcher did not find the named server: " + listed);
                }
            } finally {
                // -L leaves no -S for the fixture's sweep to match, so nothing else ends this server.
                named.killServer();
            }
        }
    }

    /** The versioned document a model sends, built here rather than pasted, so it cannot drift. */
    private static Map<String, Object> filterOn(String command) {
        return Map.of(
                "schema",
                "libtmux.filter/1",
                "model",
                "pane",
                "expr",
                Map.of("node", "compare", "field", "pane_current_command", "op", "starts_with", "value", command));
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the tool answered with no text at all"));
    }

    private static McpSyncClient launch(Path socket) {
        return launch("--socket", socket.toString(), Map.of());
    }

    /**
     * Starts the launcher as a client would, on this JVM's own classpath.
     *
     * <p>The SDK adds {@code ServerParameters}' environment to an inherited one rather than replacing
     * it, so its allowlist restricts nothing and the child holds whatever this JVM holds. What keeps
     * a launched program off the suite's own server is the build removing {@code TMUX} and
     * {@code TMUX_PANE} from every test task, asserted here so that it cannot quietly stop.
     */
    private static McpSyncClient launch(String endpoint, String value, Map<String, String> environment) {
        assertNull(System.getenv("TMUX"), "the suite is running inside tmux, so a child could inherit it");
        assertNull(System.getenv("TMUX_PANE"), "the suite is running inside a pane, so a child could inherit it");

        ServerParameters launcher = ServerParameters.builder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args(
                        "-classpath",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        endpoint,
                        value,
                        "--tmux",
                        TMUX)
                .env(environment)
                .build();
        return McpClient.sync(new StdioClientTransport(launcher, new JacksonMcpJsonMapper(new ObjectMapper())))
                .build();
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
