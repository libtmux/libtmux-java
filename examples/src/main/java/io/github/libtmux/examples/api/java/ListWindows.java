package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** List all windows, then the windows captured for one session. */
public final class ListWindows {
    private ListWindows() {}

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
            System.out.println("windows="
                    + server.windows().stream()
                            .map(window -> window.name())
                            .sorted()
                            .collect(Collectors.joining(",")));
            var session = server.session("work-one").orElseThrow();
            System.out.println("session-windows="
                    + session.windows().stream()
                            .map(window -> window.name())
                            .sorted()
                            .collect(Collectors.joining(",")));
        }
    }
}
