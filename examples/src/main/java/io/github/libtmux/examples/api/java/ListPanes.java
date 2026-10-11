package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;

/** List all panes, then the panes captured for one window. */
public final class ListPanes {
    private ListPanes() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Example failed: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String usage = "expected: tmux-binary socket-path config-file";
        if (args.length != 3) {
            throw new IllegalArgumentException(usage);
        }
        var config = ServerConfig.builder()
                .binary(args[0])
                .endpoint(ServerEndpoint.socketPath(Path.of(args[1])))
                .configFile(Path.of(args[2]))
                .build();
        try (Server server = Server.open(config)) {
            System.out.println("panes=" + server.panes().size());
            var session = server.session("work-one").orElseThrow();
            var window = session.windows().stream()
                    .filter(item -> item.name().equals("editor"))
                    .findFirst()
                    .orElseThrow();
            System.out.println("window-panes=" + window.panes().size());
        }
    }
}
