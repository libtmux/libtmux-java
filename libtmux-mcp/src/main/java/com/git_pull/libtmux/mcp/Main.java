package com.git_pull.libtmux.mcp;

import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.util.List;

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
 */
public final class Main {

    private Main() {}

    /**
     * Serves a tmux server over stdin and stdout until the client closes them.
     *
     * @param args {@code --socket PATH}, {@code --socket-name NAME}, {@code --tmux BINARY}
     */
    public static void main(String[] args) {
        ServerConfig config;
        try {
            config = configure(List.of(args));
        } catch (IllegalArgumentException e) {
            System.err.println("libtmux-mcp: " + e.getMessage());
            System.err.println("usage: libtmux-mcp [--socket PATH] [--socket-name NAME] [--tmux BINARY]");
            System.exit(2);
            return;
        }
        // The server outlives this call: the MCP transport reads stdin until the client closes it.
        Server server = Server.open(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "libtmux-mcp-shutdown"));
        System.err.println("libtmux-mcp: serving " + server.identity());

        // A client that disconnects closes this end. Without noticing that, the process outlives the
        // client that launched it, and an MCP client leaves one behind every time it restarts.
        java.util.concurrent.CountDownLatch disconnected = new java.util.concurrent.CountDownLatch(1);
        TmuxMcpServer.overStdio(server, new EndOfInputAware(System.in, disconnected::countDown));
        try {
            disconnected.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server.close();
        System.exit(0);
    }

    /** Wraps an input stream so end of input can be noticed by whoever is waiting for it. */
    private static final class EndOfInputAware extends java.io.FilterInputStream {

        private final Runnable onEnd;

        EndOfInputAware(java.io.InputStream in, Runnable onEnd) {
            super(in);
            this.onEnd = onEnd;
        }

        @Override
        public int read() throws java.io.IOException {
            return ended(super.read());
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            return ended(super.read(buffer, offset, length));
        }

        private int ended(int result) {
            if (result < 0) {
                onEnd.run();
            }
            return result;
        }
    }

    static ServerConfig configure(List<String> args) {
        ServerConfig.Builder config = ServerConfig.builder();
        for (int index = 0; index < args.size(); index++) {
            String flag = args.get(index);
            switch (flag) {
                case "--socket" -> config.endpoint(ServerEndpoint.socketPath(Path.of(value(args, ++index, flag))));
                case "--socket-name" -> config.endpoint(ServerEndpoint.namedSocket(value(args, ++index, flag)));
                case "--tmux" -> config.binary(value(args, ++index, flag));
                default -> throw new IllegalArgumentException("unknown argument '" + flag + "'");
            }
        }
        return config.build();
    }

    private static String value(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args.get(index);
    }
}
