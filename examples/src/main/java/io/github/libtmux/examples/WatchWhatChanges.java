package io.github.libtmux.examples;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.Delivery;
import io.github.libtmux.control.EventSubscription;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Watches a whole server for change, without asking it anything.
 *
 * <p>tmux re-expands a watched format on its own one-second timer and reports
 * only when the value differs, so nothing here runs between changes. That is
 * the difference between watching a server and polling one, and it is what lets
 * an agent hold a terminal open cheaply.
 *
 * <pre>{@code
 * java WatchWhatChanges.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class WatchWhatChanges {

    private static final String NEW_WINDOW = "watched-into-existence";
    private static final String DEMO = "/tmp/libtmux-java-dev/demo/s";

    private WatchWhatChanges() {}

    public static void main(String[] args) {
        Path socket = Path.of(args.length > 0 ? args[0] : DEMO);
        Duration limit = Duration.ofSeconds(10);
        run(socket, limit, event -> System.out.println(describe(event)));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader
     * runs.
     *
     * @return every change seen before the deadline
     */
    public static List<ControlEvent> run(
            Path socket,
            Duration watchFor,
            // Called with each change as it arrives.
            Consumer<ControlEvent> onChange) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            return watch(server, watchFor, onChange);
        }
    }

    private static List<ControlEvent> watch(
            Server server,
            Duration watchFor,
            // Called with each change as it arrives.
            Consumer<ControlEvent> onChange) {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                var events = client.subscribeEvents(32)) {
            // Every window's name, reported whenever one of them changes. The
            // comparison happens inside tmux; this client is idle until
            // something is different.
            client.watch("names", "@*", "#{window_name}");

            session.newWindow(NEW_WINDOW);

            return collect(events, watchFor, onChange);
        }
    }

    private static List<ControlEvent> collect(
            EventSubscription<ControlEvent> events,
            Duration watchFor,
            // Called with each change as it arrives.
            Consumer<ControlEvent> onChange) {
        List<ControlEvent> seen = new ArrayList<>();
        long deadline = System.nanoTime() + watchFor.toNanos();
        while (System.nanoTime() < deadline && !sawTheNewWindow(seen)) {
            try {
                long left = Math.max(0L, deadline - System.nanoTime());
                var next = events.next(Duration.ofNanos(left));
                if (next.isEmpty()) {
                    break;
                }
                ControlEvent arrived = Delivery.kept(next.orElseThrow());
                seen.add(arrived);
                onChange.accept(arrived);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return List.copyOf(seen);
    }

    /** Whether the watch has reported the window this example made. */
    public static boolean sawTheNewWindow(List<ControlEvent> seen) {
        return seen.stream().anyMatch(event -> named(event, NEW_WINDOW));
    }

    private static boolean named(ControlEvent event, String name) {
        return event.value().filter(name::equals).isPresent();
    }

    private static String describe(ControlEvent event) {
        return event.subscription()
                .map(name -> name + " → " + event.value().orElse(""))
                .orElseGet(() -> event.kind() + " " + event.fields());
    }
}
