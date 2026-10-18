package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Runs the tmux MCP server over stdin and stdout.
 *
 * <p>An MCP client launches this as a subprocess and speaks JSON-RPC over its standard streams, so
 * <strong>nothing may write to stdout but the protocol</strong>. Diagnostics go to stderr, which the
 * client is free to log or discard.
 *
 * <p>Which tmux server to expose is chosen the same way the library chooses it: a socket path, a
 * socket name, or tmux's own default.
 *
 * <pre>{@code
 * libtmux-mcp --socket /run/user/1000/tmux/default
 * libtmux-mcp --socket-name work --tmux /usr/local/bin/tmux
 * }</pre>
 *
 * <p>The unordered toolset and named-tool environment variables resolve one immutable surface
 * before tmux is opened. A tool outside it is neither listed nor callable.
 *
 * <p>A tmux older than 3.3a, or one that reports no release, is refused at startup: one line on
 * stderr naming the version required and what to do, and a non-zero exit, with no stack trace.
 */
public final class Main {

    private Main() {}

    /**
     * Serves a tmux server over stdin and stdout until the client closes them.
     *
     * @param args {@code --socket PATH}, {@code --socket-name NAME}, {@code --tmux BINARY}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.in, System.err));
    }

    /** The process, with its streams passed in; returns the exit status rather than exiting. */
    static int run(String[] args, Map<String, String> environment, InputStream stdin, PrintStream err) {
        LaunchConfiguration launch;
        try {
            List<String> given = List.of(args);
            ToolSurface.resolve(environment);
            launch = LaunchConfiguration.resolve(given, environment);
        } catch (IllegalArgumentException e) {
            err.println("libtmux-mcp: " + e.getMessage());
            err.println("usage: libtmux-mcp [--socket PATH] [--socket-name NAME] [--tmux BINARY]");
            return 2;
        }
        // The server outlives setup: the MCP transport reads stdin until the client closes it.
        // Lexical ownership also releases its process transport when protocol startup fails.
        try (Server server = Server.open(launch.config())) {
            // Checks the daemon, or the binary when no daemon is running, so a tmux below the floor is
            // refused here and not at the first tool call.
            server.requireCompatibleTmux();
            SocketProfile profile = launch.profile(server);
            ToolSurface surface = ToolSurface.resolve(environment, profile);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "libtmux-mcp-shutdown"));
            err.println("libtmux-mcp: serving " + server.identity() + " on " + profile.selector()
                    + " (server_state=" + profile.serverState()
                    + ", configuration_provenance=" + profile.configurationProvenance() + ") with toolsets "
                    + surface.toolsetNames() + " (" + surface.tools().size()
                    + " tools, host_command_tools=0)");

            // A client that disconnects closes this end. Without noticing that, the process outlives
            // the client that launched it, and an MCP client leaves one behind every time it restarts.
            CountDownLatch disconnected = new CountDownLatch(1);
            var mcp = TmuxMcpServer.overStdio(server, stdin, surface, disconnected::countDown);
            Runtime.getRuntime().addShutdownHook(new Thread(mcp::close, "libtmux-mcp-protocol-shutdown"));
            try {
                disconnected.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mcp.closeGracefully();
        } catch (UnsupportedFeatureException | MalformedResponseException refused) {
            // The message already says what was found, what is required and what to do.
            err.println("libtmux-mcp: " + refused.getMessage());
            return 1;
        }
        return 0;
    }

    static ServerConfig configure(List<String> args) {
        return configure(args, Map.of());
    }

    static ServerConfig configure(List<String> args, Map<String, String> environment) {
        return LaunchConfiguration.resolve(args, environment).config();
    }
}
