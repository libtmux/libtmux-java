package io.github.libtmux.examples.api.java;

import static java.util.stream.Collectors.joining;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session_;
import java.nio.file.Path;

/** Select sessions with a typed name filter. */
public final class Query {
    private Query() {}

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
            var matches = server.sessions(Session_.name().is("work-one"));
            var names = matches.stream().map(s -> s.name()).sorted();
            System.out.println("matches=" + names.collect(joining(",")));
        }
    }
}
