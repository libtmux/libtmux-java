package io.github.libtmux.examples;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Watches what a pane prints, as tmux pushes it, rather than polling for it.
 *
 * <pre>{@code
 * java WatchPaneOutput.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class WatchPaneOutput {

    private static final String ARENA_ARTIFACT = "java-watch-pane-output";
    private static final Duration DEFAULT_WATCH = Duration.ofSeconds(10);

    private WatchPaneOutput() {}

    public static void main(String[] args) {
        Optional<ServerConfig> arena = arenaConfig(System.getenv());
        if (arena.isPresent()) {
            System.out.println("LIBTMUX_ARENA_EVIDENCE="
                    + ArenaSupport.run(ARENA_ARTIFACT, arena.orElseThrow(), WatchPaneOutput::run));
            return;
        }
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        run(socket, DEFAULT_WATCH, output -> System.out.print(output.data()));
    }

    static Optional<ServerConfig> arenaConfig(Map<String, String> environment) {
        return ArenaSupport.config(environment, ARENA_ARTIFACT);
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

        try (Server server = Server.open(config)) {
            return run(server, watchFor, onOutput);
        }
    }

    /** The lent-server entry point the arena runner drives; nothing here writes to stdout. */
    static List<PaneOutput> run(Server server) {
        return run(server, DEFAULT_WATCH, output -> {});
    }

    static List<PaneOutput> run(Server server, Duration watchFor, Consumer<PaneOutput> onOutput) {
        List<PaneOutput> seen = new ArrayList<>();

        Session session = ArenaSupport.ownSession(server, "watch-pane-output");

        // Attaching is what makes tmux push %output at all. A client that never attaches hears
        // about command replies and nothing else.
        try (ControlClient client = ControlClient.attach(server.config(), session.id());
                EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {
            client.send("send-keys", "-t", session.id().value(), "echo watched", "Enter");

            long deadline = System.nanoTime() + watchFor.toNanos();
            while (System.nanoTime() < deadline && seen.isEmpty()) {
                try {
                    var next = output.next(Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())));
                    if (next.isEmpty()) {
                        break;
                    }
                    PaneOutput arrived = next.orElseThrow();
                    seen.add(arrived);
                    onOutput.accept(arrived);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return List.copyOf(seen);
    }
}
