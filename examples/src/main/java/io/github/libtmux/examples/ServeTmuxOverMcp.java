package io.github.libtmux.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.mcp.TmuxMcpServer;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serves a tmux server over MCP and reports the tool surface an agent would see.
 *
 * <pre>{@code
 * java ServeTmuxOverMcp.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 *
 * <p>The surface is fixed: one catalog decides which tools exist, and {@code LIBTMUX_TOOLSETS} and
 * {@code LIBTMUX_TOOLS} decide which of them this process offers. Reading it back is how you check
 * what a given configuration actually exposes, without attaching an agent to find out.
 *
 * <p>Expect fewer tools than the catalog holds when the socket already has a server on it: teardown
 * is enabled by default only for a minimal daemon this process started, so attaching to somebody
 * else's tmux does not hand an agent the tools that end it.
 */
public final class ServeTmuxOverMcp {

    private static final String ARENA_ARTIFACT = "java-serve-tmux-over-mcp";

    private ServeTmuxOverMcp() {}

    public static void main(String[] args) {
        Optional<ServerConfig> arena = arenaConfig(System.getenv());
        if (arena.isPresent()) {
            System.out.println("LIBTMUX_ARENA_EVIDENCE="
                    + ArenaSupport.run(ARENA_ARTIFACT, arena.orElseThrow(), ServeTmuxOverMcp::run));
            return;
        }
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        run(socket).forEach(System.out::println);
    }

    static Optional<ServerConfig> arenaConfig(Map<String, String> environment) {
        return ArenaSupport.config(environment, ARENA_ARTIFACT);
    }

    /** Separated from {@code main} so the suite can run exactly what a reader runs. */
    public static List<String> run(Path socket) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            return run(server);
        }
    }

    static List<String> run(Server server) {
        // A real client speaks over this process's stdin and stdout, which TmuxMcpServer.overStdio
        // wires up. Here the streams are empty and discarded: the point is the surface, not a
        // conversation, and an example that wrote JSON-RPC to stdout could not also print.
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(new ObjectMapper()),
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream());

        // Serving hands the transport over; closing the returned server closes it.
        McpSyncServer mcp = TmuxMcpServer.serving(server, transport);
        try {
            return mcp.listTools().stream().map(McpSchema.Tool::name).sorted().toList();
        } finally {
            mcp.close();
        }
    }
}
