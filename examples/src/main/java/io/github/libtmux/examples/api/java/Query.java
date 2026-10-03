package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session_;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Select sessions with a typed name filter. */
public final class Query {
    private Query() {}

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
                var matches = server.sessions(Session_.name().is("work-one"));
                System.out.println("matches="
                        + matches.stream()
                                .map(session -> session.name())
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
