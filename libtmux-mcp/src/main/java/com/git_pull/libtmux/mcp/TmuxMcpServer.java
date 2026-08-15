package com.git_pull.libtmux.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Server;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exposes a tmux server to a model over the Model Context Protocol.
 *
 * <p>Thin by design. The tools themselves live in {@link TmuxTools}, which is what gets tested
 * against real tmux; this class only describes them and turns their answers into text.
 *
 * <p>A tool that fails returns the failure as a tool error rather than throwing. A model can act on
 * "no pane %9" — by listing panes again — and cannot act on a transport-level exception.
 */
public final class TmuxMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TmuxMcpServer() {}

    /** Serves a tmux server over stdin and stdout, which is how an MCP client launches a tool. */
    public static McpSyncServer overStdio(Server server) {
        return overStdio(server, System.in);
    }

    /**
     * Serves a tmux server over a caller-supplied input stream and stdout.
     *
     * <p>Taking the stream lets a launcher notice end of input for itself. A client that
     * disconnects closes this end, and a server that did not notice would outlive it.
     */
    public static McpSyncServer overStdio(Server server, java.io.InputStream in) {
        return serving(
                server, new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()), in, System.out));
    }

    /** Serves a tmux server over a caller-supplied transport. */
    public static McpSyncServer serving(Server server, McpServerTransportProvider transport) {
        TmuxTools tools = new TmuxTools(server);
        return McpServer.sync(transport)
                .serverInfo("libtmux", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(
                        tool("tmux_list_sessions", "Lists tmux sessions and the windows in each.", Map.of()),
                        (exchange, request) -> text(() -> render(tools.sessions())))
                .toolCall(
                        tool("tmux_list_panes", "Lists every pane with the id other tools take as a target.", Map.of()),
                        (exchange, request) -> text(() -> render(tools.panes())))
                .toolCall(
                        tool(
                                "tmux_capture_pane",
                                "Returns what a pane is currently showing.",
                                Map.of("pane_id", "The pane id, such as %1.")),
                        (exchange, request) -> text(() -> render(tools.capture(argument(request, "pane_id")))))
                .toolCall(
                        tool(
                                "tmux_run",
                                "Runs a command in a pane, as though it had been typed there.",
                                Map.of("pane_id", "The pane id, such as %1.", "command", "The command to run.")),
                        (exchange, request) -> text(() -> {
                            tools.run(argument(request, "pane_id"), argument(request, "command"));
                            return render(java.util.Map.of("sent", true));
                        }))
                .toolCall(
                        tool(
                                "tmux_new_window",
                                "Creates a window in a session and returns its first pane id.",
                                Map.of("session", "The session name.", "name", "The window name.")),
                        (exchange, request) -> text(() -> render(java.util.Map.of(
                                "pane_id", tools.newWindow(argument(request, "session"), argument(request, "name"))))))
                .build();
    }

    /** Every tool here takes plain string arguments, so one schema shape covers all of them. */
    private static McpSchema.Tool tool(String name, String description, Map<String, String> arguments) {
        Map<String, Object> properties = new LinkedHashMap<>();
        arguments.forEach(
                (argument, describes) -> properties.put(argument, Map.of("type", "string", "description", describes)));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(arguments.keySet()));
        return McpSchema.Tool.builder(name, schema).description(description).build();
    }

    private static String argument(McpSchema.CallToolRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing argument '" + name + "'");
        }
        return value.toString();
    }

    /**
     * Turns an answer into a tool result, and a failure into a tool error.
     *
     * <p>A model can do something with "no pane %9" — list panes again — and can do nothing with an
     * exception that never reaches it.
     */
    private static McpSchema.CallToolResult text(Supplier<String> answer) {
        try {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(null, answer.get(), null)))
                    .isError(false)
                    .build();
        } catch (LibTmuxException | IllegalArgumentException e) {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(null, String.valueOf(e.getMessage()), null)))
                    .isError(true)
                    .build();
        }
    }

    /**
     * Answers with JSON.
     *
     * <p>A model reads these, and a Java record's own rendering would make it reverse-engineer
     * {@code PaneSummary[id=%0, window=zsh]} to find a pane id it is then supposed to pass back.
     */
    private static String render(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("could not render a tool result", e);
        }
    }
}
