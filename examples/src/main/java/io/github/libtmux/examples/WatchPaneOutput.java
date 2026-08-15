package io.github.libtmux.examples;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.PaneOutput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Watches what a pane prints, as tmux pushes it, rather than polling for it.
 *
 * <pre>{@code
 * java WatchPaneOutput.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class WatchPaneOutput {

    private WatchPaneOutput() {}

    public static void main(String[] args) {
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        run(socket, Duration.ofSeconds(10), output -> System.out.print(output.data()));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader runs.
     *
     * @return everything seen before the deadline
     */
    public static List<PaneOutput> run(Path socket, Duration watchFor, Consumer<PaneOutput> onOutput) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        List<PaneOutput> seen = new CopyOnWriteArrayList<>();
        try (Server server = Server.open(config)) {
            Session session = server.sessions().get(0);

            // Attaching is what makes tmux push %output at all. A client that never attaches hears
            // about command replies and nothing else.
            try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
                client.onOutput(output -> {
                    seen.add(output);
                    onOutput.accept(output);
                });
                client.send("send-keys", "-t", session.name(), "echo watched", "Enter");

                long deadline = System.nanoTime() + watchFor.toNanos();
                while (System.nanoTime() < deadline && seen.isEmpty()) {
                    Thread.onSpinWait();
                }
            }
        }
        return List.copyOf(seen);
    }
}
