package io.github.libtmux.examples.api.java;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.TextOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Send input and wait for output before capturing the pane. */
public final class Capture {
    private Capture() {}

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
                var session = server.newSession(SessionSpec.builder()
                        .named("api-capture")
                        .running("/bin/sh")
                        .build());
                var pane = session.windows().getFirst().panes().getFirst();
                pane.sendKeys(List.of("printf 'api-%s\\n' keys", "Enter"));
                if (pane.awaitText("api-keys", Duration.ofSeconds(30)) == TextOutcome.TIMED_OUT) {
                    throw new IllegalStateException("timed out waiting for keys output");
                }
                pane.sendLine("printf 'api-%s\\n' line");
                if (pane.awaitText("api-line", Duration.ofSeconds(30)) == TextOutcome.TIMED_OUT) {
                    throw new IllegalStateException("timed out waiting for line output");
                }
                var lines = pane.capture();
                for (String expected : List.of("api-keys", "api-line")) {
                    if (lines.stream().noneMatch(line -> line.strip().equals(expected))) {
                        throw new IllegalStateException("capture is missing " + expected);
                    }
                    System.out.println(expected);
                }
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Example failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
