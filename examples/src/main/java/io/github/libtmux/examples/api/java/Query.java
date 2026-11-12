package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.Session_;
import io.github.libtmux.WindowSpec;
import java.util.stream.Collectors;

/** Select sessions with a typed name filter. */
public final class Query {
    private Query() {}

    public static void main(String[] args) {
        try {
            try (Server server = Server.start()) {
                var workOne = server.newSession(SessionSpec.builder()
                        .named("work-one")
                        .firstWindowNamed("editor")
                        .running("/bin/cat")
                        .build());
                workOne.windows().getFirst().split(split -> split.toRight().running("/bin/cat"));
                workOne.newWindow(WindowSpec.builder()
                        .named("logs")
                        .running("/bin/cat")
                        .detached()
                        .build());
                server.newSession(SessionSpec.builder()
                        .named("work-two")
                        .firstWindowNamed("editor")
                        .running("/bin/cat")
                        .build());
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
