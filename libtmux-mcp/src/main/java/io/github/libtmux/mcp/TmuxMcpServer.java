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
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Exposes a tmux server to a model over the Model Context Protocol.
 *
 * <p>Thin by design. What the tools do lives in {@link Catalog} and the classes it names, which is
 * what gets tested against real tmux; this class describes them to a client and turns their answers
 * into protocol.
 *
 * <p>The SDK dispatches synchronous handlers on its bounded worker scheduler, not the transport I/O
 * thread. Core operations remain typed and synchronous; wait and channel operations retain their
 * own cancellation and timeout contracts.
 */
public final class TmuxMcpServer {

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
        return overStdio(server, System.in, ToolSurface.resolve(System.getenv()), () -> {});
    }

    /**
     * Serves a tmux server over a caller-supplied input stream and stdout.
     *
     * <p>Taking the stream lets a launcher notice end of input for itself. A client that disconnects
     * closes this end, and a server that did not notice would outlive it.
     *
     * <p>The returned server owns and closes the input when startup fails, either protocol stream
     * disconnects, or the server closes.
     *
     */
    static McpSyncServer overStdio(Server server, InputStream in, ToolSurface surface) {
        return overStdio(server, in, surface, () -> {});
    }

    static McpSyncServer overStdio(Server server, InputStream in, ToolSurface surface, Runnable onSessionEnd) {
        return overStdio(server, in, System.out, surface, onSessionEnd);
    }

    static McpSyncServer overStdio(
            Server server, InputStream in, OutputStream out, ToolSurface surface, Runnable onSessionEnd) {
        SessionLifetime lifetime = new SessionLifetime(onSessionEnd);
        lifetime.own(in);
        try {
            var provider = new StdioServerTransportProvider(
                    new JacksonMcpJsonMapper(new ObjectMapper()), in, lifetime.observe(out));
            lifetime.own(provider::close);
            return serving(server, surface, lifetime.observe(provider), lifetime);
        } catch (RuntimeException | Error failure) {
            lifetime.endAfter(failure);
            throw failure;
        }
    }

    /**
     * Serves a tmux server over a caller-supplied transport.
     *
     * <p>The returned server owns the transport. Ownership transfers on entry, so failed startup
     * closes it too.
     */
    public static McpSyncServer serving(Server server, McpServerTransportProvider transport) {
        return serving(server, ToolSurface.resolve(System.getenv()), transport);
    }

    static McpSyncServer serving(Server server, ToolSurface surface, McpServerTransportProvider transport) {
        return serving(server, surface, transport, null);
    }

    private static McpSyncServer serving(
            Server server,
            ToolSurface surface,
            McpServerTransportProvider transport,
            @Nullable SessionLifetime lifetime) {
        Objects.requireNonNull(transport, "transport");
        @Nullable McpSyncServer built = null;
        try {
            Connection connection = Connection.to(server, surface);
            built = build(connection, transport);
            return built;
        } catch (RuntimeException | Error failure) {
            Cleanup cleanup = new Cleanup(failure);
            if (lifetime == null) {
                if (built == null) {
                    cleanup.run(transport::close);
                } else {
                    McpSyncServer accepted = built;
                    cleanup.run(accepted::close);
                }
            } else if (built != null) {
                McpSyncServer accepted = built;
                cleanup.run(accepted::close);
            }
            throw failure;
        }
    }

    private static McpSyncServer build(Connection connection, McpServerTransportProvider transport) {
        var specification = McpServer.sync(new SerializedTransportProvider(transport))
                .serverInfo("libtmux", version())
                .instructions(Instructions.forServer(connection))
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .logging()
                        .build())
                .resources(Resources.fixed(connection));

        for (ToolSpec tool : connection.surface().tools().values()) {
            specification = specification.toolCall(
                    tool.describe(), (exchange, request) -> answer(connection, tool, exchange, request));
        }
        return specification.build();
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
            connection.surface().require(tool.name());
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            tool.validateArguments(arguments);
            Call call = connection.call(arguments, progress(exchange, request));
            Object value = tool.answer().apply(call);
            tool.validateOutput(value);
            return Answers.ok(value);
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
