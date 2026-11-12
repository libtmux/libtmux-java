package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.WindowSpec;
import java.util.stream.Collectors;

/** Create a window and read the updated session. */
public final class NewWindow {
    private NewWindow() {}

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
