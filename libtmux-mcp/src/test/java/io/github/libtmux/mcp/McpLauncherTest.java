package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

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

    /**
     * Starts the launcher as a client would, on this JVM's own classpath.
     *
     * <p>The SDK passes the child only an allowlist of environment variables, which excludes
     * {@code TMUX}; so the child cannot inherit the suite's own server even by accident, and the
     * socket it answers about is the one named on its command line or none.
     */
    private static McpSyncClient launch(Path socket) {
        ServerParameters launcher = ServerParameters.builder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args(
                        "-classpath",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        "--socket",
                        socket.toString(),
                        "--tmux",
                        TMUX)
                .build();
        return McpClient.sync(new StdioClientTransport(launcher, new JacksonMcpJsonMapper(new ObjectMapper())))
                .build();
    }
}
