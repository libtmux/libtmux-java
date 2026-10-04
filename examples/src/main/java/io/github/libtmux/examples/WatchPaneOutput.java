package io.github.libtmux.examples;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.Delivery;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutput;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Watches what a pane prints, as tmux pushes it, rather than polling for it.
 *
 * <pre>{@code
 * java WatchPaneOutput.java /tmp/libtmux-java-dev/demo/s
 * }</pre>
 */
public final class WatchPaneOutput {

    private static final String DEMO = "/tmp/libtmux-java-dev/demo/s";

    private WatchPaneOutput() {}

    public static void main(String[] args) {
        Path socket = Path.of(args.length > 0 ? args[0] : DEMO);
        Duration limit = Duration.ofSeconds(10);
        run(socket, limit, output -> System.out.print(output.data()));
    }

    /**
     * Separated from {@code main} so the suite can run exactly what a reader
     * runs. A {@link Delivery.Gap} fails the watch: this reader wants every
     * output, and a gap means some was discarded.
     *
     * @return everything seen before the deadline
     */
    public static List<PaneOutput> run(
            Path socket,
            Duration watchFor,
            // Called with each output as it arrives.
            Consumer<PaneOutput> onOutput) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            return watch(server, watchFor, onOutput);
        }
    }

    private static List<PaneOutput> watch(
            Server server,
            Duration watchFor,
            // Called with each output as it arrives.
            Consumer<PaneOutput> onOutput) {
        Session session = server.sessions().get(0);
        String target = session.id().value();

        // Attaching is what makes tmux push %output at all. A client that
        // never attaches hears about command replies and nothing else.
        try (ControlClient client = server.control(session);
                var feed = client.subscribeOutput(32)) {
            client.send("send-keys", "-t", target, "echo watched", "Enter");
            closeAfter(watchFor, feed);
            return collect(feed, onOutput);
        }
    }

    // Closing the subscription at the deadline ends the stream if the echo
    // never comes.
    private static void closeAfter(Duration wait, EventSubscription<?> feed) {
        long nanos = wait.toNanos();
        Executor timer = CompletableFuture.delayedExecutor(nanos, NANOSECONDS);
        timer.execute(feed::close);
    }

    private static List<PaneOutput> collect(
            EventSubscription<PaneOutput> feed,
            // Called with each output as it arrives.
            Consumer<PaneOutput> onOutput) {
        List<PaneOutput> seen = new ArrayList<>();
        try (Stream<Delivery<PaneOutput>> steps = feed.stream()) {
            Iterator<PaneOutput> outputs = steps.map(Delivery::kept).iterator();
            while (!sawTheEcho(seen) && outputs.hasNext()) {
                PaneOutput arrived = outputs.next();
                seen.add(arrived);
                onOutput.accept(arrived);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Whether the output holds the line {@code echo} printed, not only the keys
     * typed to run it. The typed line reads {@code echo watched}, so the
     * printed one is {@code watched} on a line of its own.
     */
    public static boolean sawTheEcho(List<PaneOutput> seen) {
        Stream<String> chunks = seen.stream().map(PaneOutput::data);
        return printedLine(chunks.reduce("", String::concat), "watched");
    }

    /**
     * Whether the output has a line that is exactly {@code text}, once terminal
     * control sequences are removed. A shell can put a mode switch and a
     * carriage return in front of a printed line, so the line cannot be matched
     * as the raw bytes after a newline.
     */
    public static boolean printedLine(String output, String text) {
        String control = "\u001B\\[[0-9;?]*[A-Za-z]";
        UnaryOperator<String> plain = s -> s.replaceAll(control, "").strip();
        return output.lines().map(plain).anyMatch(text::equals);
    }
}
