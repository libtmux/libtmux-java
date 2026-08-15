package com.git_pull.libtmux.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.jackson.FilterJson;
import com.git_pull.libtmux.jackson.LibTmuxModels;
import com.git_pull.libtmux.query.FilterExpr;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.InputStream;
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

    /**
     * The filter document shown to a model, and the only one it is given to copy.
     *
     * <p>A constant rather than prose inside the description, because an example that stopped parsing
     * would still read perfectly. {@code TmuxMcpServerTest} parses this one.
     */
    static final String EXAMPLE_FILTER = "{\"schema\":\"" + FilterJson.SCHEMA + "\",\"model\":\"pane\","
            + "\"expr\":{\"node\":\"compare\",\"field\":\"pane_current_command\","
            + "\"op\":\"starts_with\",\"value\":\"nvim\"}}";

    private TmuxMcpServer() {}

    /**
     * What this build calls itself, as the jar's manifest records it.
     *
     * <p>Read rather than written down. A version repeated in source drifts from the one the build
     * publishes, and a model told the wrong one has no way to notice. Outside a jar — a test, an IDE
     * — there is no manifest, and "unreleased" is the honest answer rather than a stale number.
     */
    private static String version() {
        String stated = TmuxMcpServer.class.getPackage().getImplementationVersion();
        return stated == null ? "unreleased" : stated;
    }

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
    public static McpSyncServer overStdio(Server server, InputStream in) {
        return serving(
                server, new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()), in, System.out));
    }

    /** Serves a tmux server over a caller-supplied transport. */
    public static McpSyncServer serving(Server server, McpServerTransportProvider transport) {
        TmuxTools tools = new TmuxTools(server);
        return McpServer.sync(transport)
                .serverInfo("libtmux", version())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(
                        tool("tmux_list_sessions", "Lists tmux sessions and the windows in each.", Map.of()),
                        (exchange, request) -> text(() -> render(tools.sessions())))
                .toolCall(
                        listPanesTool(),
                        (exchange, request) -> text(() -> render(tools.describe(chosen(server, request)))))
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
                            return render(Map.of("sent", true));
                        }))
                .toolCall(
                        tool(
                                "tmux_new_window",
                                "Creates a window in a session and returns its first pane id.",
                                Map.of("session", "The session name.", "name", "The window name.")),
                        (exchange, request) -> text(() -> render(Map.of(
                                "pane_id", tools.newWindow(argument(request, "session"), argument(request, "name"))))))
                .build();
    }

    /**
     * The one tool with a structured, optional argument, so it describes itself rather than bending
     * the string-argument helper into covering a case it does not have.
     *
     * <p>The filter is the same versioned document every port of libtmux reads, and its field names
     * are tmux format names — {@code pane_current_command} rather than anything Java calls a field —
     * so a model that has seen the schema once can write one for any of them.
     */
    private static McpSchema.Tool listPanesTool() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("type", "object");
        filter.put(
                "description",
                "A " + FilterJson.SCHEMA + " document over the pane model, for example " + EXAMPLE_FILTER
                        + ". Omit it to list every pane.");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("filter", filter));
        schema.put("required", List.of());
        return McpSchema.Tool.builder("tmux_list_panes", schema)
                .description("Lists panes with the id other tools take as a target, optionally narrowed by a filter.")
                .build();
    }

    /**
     * The panes a request asked about: every one, or those its filter selects.
     *
     * <p>One capture either way. Filtering happens over what that capture already returned, so a
     * narrower answer costs no more tmux commands than the whole listing does.
     */
    private static List<Pane> chosen(Server server, McpSchema.CallToolRequest request) {
        List<Pane> panes = server.panes();
        Object filter = request.arguments().get("filter");
        if (filter == null) {
            return panes;
        }
        FilterExpr<Pane> expression = FilterJson.read(JSON.valueToTree(filter), LibTmuxModels.pane());
        return panes.stream().filter(expression).toList();
    }

    /** Every other tool here takes plain string arguments, so one schema shape covers all of them. */
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
