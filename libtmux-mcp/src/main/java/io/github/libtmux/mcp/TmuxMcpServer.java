package io.github.libtmux.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Exposes a tmux server to a model over the Model Context Protocol.
 *
 * <p>Thin by design. What the tools do lives in {@link Catalog} and the classes it names, which is
 * what gets tested against real tmux; this class describes them to a client and turns their answers
 * into protocol.
 *
 * <p>Synchronous, and deliberately. The SDK runs a synchronous handler on
 * {@code Schedulers.boundedElastic} rather than on the thread reading the transport, so a tool that
 * blocks for a minute does not stop the connection answering anything else — measured at twenty
 * interleaved calls served during one six-second call. Writing the same handlers as reactive
 * pipelines measured worse: a {@code Mono.fromCallable} that blocks pins the single reactor thread
 * and serves nothing at all until it lets go.
 */
public final class TmuxMcpServer {

    /**
     * How long the SDK waits for a client to answer something this server asked it.
     *
     * <p>Not a bound on a tool call: those bound themselves. Generous because the wait tools may
     * legitimately hold a request open to the wait ceiling.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

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
        return overStdio(server, System.in, Safety.MUTATING, false);
    }

    /**
     * Serves a tmux server over a caller-supplied input stream and stdout.
     *
     * <p>Taking the stream lets a launcher notice end of input for itself. A client that disconnects
     * closes this end, and a server that did not notice would outlive it.
     *
     * @param watching whether to attach a control client and push notifications as tmux changes
     */
    public static McpSyncServer overStdio(Server server, InputStream in, Safety ceiling, boolean watching) {
        return serving(
                server,
                ceiling,
                watching,
                new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()), in, System.out));
    }

    /** Serves a tmux server over a caller-supplied transport. */
    public static McpSyncServer serving(Server server, Safety ceiling, McpServerTransportProvider transport) {
        return serving(server, ceiling, false, transport);
    }

    /** Serves a tmux server over a caller-supplied transport, optionally watching it for changes. */
    public static McpSyncServer serving(
            Server server, Safety ceiling, boolean watching, McpServerTransportProvider transport) {
        Connection connection = Connection.to(server, ceiling);
        var specification = McpServer.sync(transport)
                .serverInfo("libtmux", version())
                .instructions(Instructions.forServer(ceiling, watching))
                .requestTimeout(REQUEST_TIMEOUT)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        // Subscription is offered only when something is actually watching tmux.
                        // Advertising it otherwise invites a client to subscribe and wait forever for
                        // an update nothing will ever send.
                        .resources(watching, true)
                        .prompts(false)
                        .completions()
                        .logging()
                        .build())
                .resources(Resources.fixed(connection))
                .resourceTemplates(Resources.templated(connection))
                .prompts(Prompts.all())
                .completions(Completions.all(connection));

        for (ToolSpec tool : Catalog.offered(ceiling).values()) {
            specification = specification.toolCall(
                    tool.describe(), (exchange, request) -> answer(connection, tool, exchange, request));
        }
        McpSyncServer mcp = specification.build();
        if (watching) {
            // Started after the server exists, because a notification has nowhere to go before that.
            Watches.Notifier notifier = new Watches.Notifier() {
                @Override
                public void updated(String uri) {
                    mcp.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(uri));
                }

                @Override
                public void listChanged() {
                    mcp.notifyResourcesListChanged();
                }
            };
            Watches.start(connection, notifier)
                    .ifPresent(watches ->
                            Runtime.getRuntime().addShutdownHook(new Thread(watches::close, "libtmux-mcp-watches")));
        }
        return mcp;
    }

    /**
     * Runs one tool and turns whatever happens into something a model can act on.
     *
     * <p>A failure is a tool error rather than a thrown exception, because a transport-level
     * exception never reaches the model — and the model is the one participant able to choose a
     * different pane.
     */
    private static McpSchema.CallToolResult answer(
            Connection connection, ToolSpec tool, McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            Call call = connection.call(arguments, progress(exchange, request));
            return Answers.ok(tool.answer().apply(call));
        } catch (LibTmuxException | IllegalArgumentException | IllegalStateException e) {
            return Answers.failure(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Reports how a slow tool is going, when the client asked to be told.
     *
     * <p>A client sends a progress token only when it wants notifications; without one, sending them
     * would be talking to nobody. A failure to report is swallowed: the client has usually gone, and
     * a wait must not fail because nobody was listening to it.
     */
    private static Call.Progress progress(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object token = token(request);
        if (token == null) {
            return Call.Progress.SILENT;
        }
        return (elapsed, total, message) -> {
            try {
                exchange.progressNotification(McpSchema.ProgressNotification.builder(token, elapsed.toMillis() / 1000.0)
                        .total(total.toMillis() / 1000.0)
                        .message(message)
                        .build());
            } catch (RuntimeException e) {
                // The client stopped listening. What it asked for still has to finish.
            }
        };
    }

    private static @Nullable Object token(McpSchema.CallToolRequest request) {
        Map<String, Object> meta = request.meta();
        return meta == null ? null : meta.get("progressToken");
    }
}
