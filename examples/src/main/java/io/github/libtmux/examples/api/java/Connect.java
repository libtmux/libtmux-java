package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;

/** Connect to a private tmux server. */
public final class Connect {
    private Connect() {}

    public static void main(String[] args) {
        try {
            try (Server server = Server.start()) {
                System.out.println("connected=" + server.isAlive());
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Example failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
