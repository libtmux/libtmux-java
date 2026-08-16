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
 * The launcher an MCP client starts, started that way. A wrong endpoint passes every in-process
 * test in this module and still serves a model the wrong machine's tmux.
 */
@ExtendWith(TmuxExtension.class)
final class McpLauncherTest {

    /** Named explicitly: a child resolving tmux from PATH would answer about a different build. */
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
            assertTrue(missing.contains("\"count\":0"), "a filter matching nothing returned panes: " + missing);
            assertFalse(missing.contains("\"id\":\"%"), "a filter matching nothing must not return everything");
            assertTrue(
                    missing.contains("without 'filter'"),
                    "an empty answer has to say whether the server was empty or the filter was wrong: " + missing);
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
            assertTrue(instructions.contains("tmux_whoami"), "a model has to be told how to find its own pane");
            assertTrue(instructions.contains("Do NOT use them for browser tabs"), "anti-triggers must be stated");
            assertTrue(instructions.contains("SAFETY"), "and what it is not allowed to do");
        }
    }

    /** Resources, prompts and completion are all advertised, or a client will never ask for them. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void theOtherHalvesOfTheProtocolAreOfferedToo(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            List<String> resources = client.listResources().resources().stream()
                    .map(McpSchema.Resource::uri)
                    .toList();
            List<String> templates = client.listResourceTemplates().resourceTemplates().stream()
                    .map(McpSchema.ResourceTemplate::uriTemplate)
                    .toList();
            List<String> prompts = client.listPrompts().prompts().stream()
                    .map(McpSchema.Prompt::name)
                    .toList();

            assertTrue(resources.contains("tmux://panes"), resources.toString());
            assertTrue(templates.contains("tmux://panes/{pane_id}/content"), templates.toString());
            assertTrue(prompts.contains("run_and_wait"), prompts.toString());
        }
    }

    /** A pane resource is the pane's own text, addressable without spending a tool call on it. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aPaneCanBeReadAsAResource(Server server, TmuxSocketPath socket) {
        String pane = server.panes().get(0).id().value();

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            McpSchema.ReadResourceResult read =
                    client.readResource(McpSchema.ReadResourceRequest.builder("tmux://panes/" + pane + "/content")
                            .build());

            assertEquals(1, read.contents().size());
            assertEquals(
                    "text/plain",
                    ((McpSchema.TextResourceContents) read.contents().get(0)).mimeType(),
                    "terminal text is not JSON and must not be labelled as it");
        }
    }

    /**
     * Completion answered from tmux rather than from a fixed list. Without it, finding a pane id
     * costs a listing call, a read of that listing, and a choice.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void completionOffersThePaneIdsThatActuallyExist(Server server, TmuxSocketPath socket) {
        String pane = server.panes().get(0).id().value();

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            McpSchema.CompleteResult completed = client.completeCompletion(McpSchema.CompleteRequest.builder(
                            new McpSchema.ResourceReference("tmux://panes/{pane_id}"),
                            new McpSchema.CompleteRequest.CompleteArgument("pane_id", "%"))
                    .build());

            assertTrue(
                    completed.completion().values().contains(pane),
                    "the pane that exists was not offered: "
                            + completed.completion().values());
        }
    }

    /** What a client decides to confirm with a person on comes from these, so they have to arrive. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void everyToolArrivesWithItsRiskDeclared(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            for (McpSchema.Tool tool : client.listTools().tools()) {
                assertTrue(tool.annotations() != null, tool.name() + " arrived with no annotations");
            }
            McpSchema.Tool reading = named(client, "tmux_capture_pane");
            McpSchema.Tool running = named(client, "tmux_run");

            assertEquals(true, reading.annotations().readOnlyHint(), "reading a pane changes nothing");
            assertEquals(false, running.annotations().readOnlyHint(), "running a command does");
        }
    }

    /** A ceiling removes tools rather than refusing them, and this is where that reaches a client. */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aReadOnlyLauncherDoesNotEvenOfferTheToolsThatChangeThings(Server server, TmuxSocketPath socket) {
        try (McpSyncClient client = launch(socket.path(), "--safety", "readonly")) {
            client.initialize();

            List<String> offered = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();

            assertTrue(offered.contains("tmux_capture_pane"), offered.toString());
            assertFalse(offered.contains("tmux_run"), "a read-only server must not offer to run commands");
            assertFalse(offered.contains("tmux_kill"), offered.toString());
        }
    }

    /**
     * The flagship path over a real wire: a command sent, waited for, and its exit status handed back
     * as a number rather than something to infer from the screen.
     */
    @Test
    @Timeout(PATIENCE_SECONDS)
    void aCommandRunsAndItsExitStatusComesBack(Server server, TmuxSocketPath socket) {
        String pane = server.panes().get(0).id().value();

        try (McpSyncClient client = launch(socket.path())) {
            client.initialize();

            McpSchema.CallToolResult ran = client.callTool(McpSchema.CallToolRequest.builder("tmux_run")
                    .arguments(Map.of("pane_id", pane, "command", "echo over-the-wire; exit 7", "timeout", 30))
                    .build());

            String answer = textOf(ran);
            assertTrue(answer.contains("\"exit_status\":7"), answer);
            assertTrue(answer.contains("over-the-wire"), answer);
            assertFalse(answer.contains("wait-for"), "no plumbing may reach the model: " + answer);
            assertTrue(ran.structuredContent() != null, "a client that parses gets the object too");
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

    /** A name resolves under {@code TMUX_TMPDIR}, so it is stated rather than left to be inherited. */
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
