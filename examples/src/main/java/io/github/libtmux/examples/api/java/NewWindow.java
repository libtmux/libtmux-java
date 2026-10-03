package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.WindowSpec;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Create a window and read the updated session. */
public final class NewWindow {
    private NewWindow() {}

    public static void main(String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException("expected: tmux-binary socket-path config-file");
            }
            var config = ServerConfig.builder()
                    .binary(args[0])
                    .endpoint(ServerEndpoint.socketPath(Path.of(args[1])))
                    .configFile(Path.of(args[2]))
                    .build();
            try (Server server = Server.open(config)) {
                var session = server.session("work-one").orElseThrow();
                var window = session.newWindow(WindowSpec.builder()
                        .named("api-window")
                        .running("/bin/cat")
                        .build());
                System.out.println("created=" + window.name());
                var refreshed = server.session("work-one").orElseThrow();
                System.out.println("windows="
                        + refreshed.windows().stream()
                                .map(item -> item.name())
                                .sorted()
                                .collect(Collectors.joining(",")));
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Example failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
