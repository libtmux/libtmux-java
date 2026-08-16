package io.github.libtmux.examples;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Watches a whole server for change, without asking it anything.
 *
 * <p>tmux re-expands a watched format on its own one-second timer and reports only when the value
 * differs, so nothing here runs between changes. That is the difference between watching a server
 * and polling one, and it is what lets an agent hold a terminal open cheaply.
 *
 * <pre>{@code
 * java WatchWhatChanges.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class WatchWhatChanges {

    private WatchWhatChanges() {}

    public static void main(String[] args) {
        Path socket = Path.of(args.length > 0 ? args[0] : "/tmp/libtmux-java-dev/demo/s");
        run(socket, Duration.ofSeconds(10), event -> System.out.println(describe(event)));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader runs.
     *
     * @return every change seen before the deadline
     */
    public static List<ControlEvent> run(Path socket, Duration watchFor, Consumer<ControlEvent> onChange) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        List<ControlEvent> seen = new CopyOnWriteArrayList<>();
        try (Server server = Server.open(config)) {
            Session session = server.sessions().get(0);

            try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
                client.onEvent(event -> {
                    seen.add(event);
                    onChange.accept(event);
                });

                // Every window's name, reported whenever one of them changes. The comparison happens
                // inside tmux; this client is idle until something is different.
                client.watch("names", "@*", "#{window_name}");

                session.newWindow("watched-into-existence");

                long deadline = System.nanoTime() + watchFor.toNanos();
                while (System.nanoTime() < deadline && !sawTheNewWindow(seen)) {
                    Thread.onSpinWait();
                }
            }
        }
        return List.copyOf(seen);
    }

    /** Whether the watch has reported the window this example made. */
    public static boolean sawTheNewWindow(List<ControlEvent> seen) {
        return seen.stream()
                .anyMatch(event ->
                        event.value().filter("watched-into-existence"::equals).isPresent());
    }

    private static String describe(ControlEvent event) {
        return event.subscription()
                .map(name -> name + " → " + event.value().orElse(""))
                .orElseGet(() -> event.kind() + " " + event.fields());
    }
}
