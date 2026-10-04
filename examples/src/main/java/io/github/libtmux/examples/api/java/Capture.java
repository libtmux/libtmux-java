package io.github.libtmux.examples.api.java;

import io.github.libtmux.Pane;
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
            var session = server.newSession(SessionSpec.builder()
                    .named("api-capture")
                    .running("/bin/sh")
                    .build());
            var pane = session.windows().getFirst().panes().getFirst();
            pane.sendKeys(List.of("printf 'api-%s\\n' keys", "Enter"));
            await(pane, "api-keys");
            pane.sendLine("printf 'api-%s\\n' line");
            await(pane, "api-line");
            var lines = pane.capture().stream().map(String::strip).toList();
            for (String want : List.of("api-keys", "api-line")) {
                if (!lines.contains(want)) {
                    throw new IllegalStateException("capture lacks " + want);
                }
                System.out.println(want);
            }
        }
    }

    private static void await(Pane pane, String text) throws Exception {
        var outcome = pane.awaitText(text, Duration.ofSeconds(5));
        if (outcome == TextOutcome.TIMED_OUT) {
            throw new IllegalStateException("timed out waiting for " + text);
        }
    }
}
