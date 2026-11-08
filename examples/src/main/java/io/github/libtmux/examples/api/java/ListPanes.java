package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.WindowSpec;

/** List all panes, then the panes captured for one window. */
public final class ListPanes {
    private ListPanes() {}

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
                System.out.println("panes=" + server.panes().size());
                var session = server.session("work-one").orElseThrow();
                var window = session.windows().stream()
                        .filter(item -> item.name().equals("editor"))
                        .findFirst()
                        .orElseThrow();
                System.out.println("window-panes=" + window.panes().size());
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Example failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
