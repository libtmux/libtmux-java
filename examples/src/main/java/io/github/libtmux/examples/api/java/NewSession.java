package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.SessionSpec;

/** Create a session running cat. */
public final class NewSession {
    private NewSession() {}

    public static void main(String[] args) {
        try {
            try (Server server = Server.start()) {
                var session = server.newSession(SessionSpec.builder()
                        .named("api-session")
                        .running("/bin/cat")
                        .build());
                System.out.println("created=" + session.name());
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Example failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
