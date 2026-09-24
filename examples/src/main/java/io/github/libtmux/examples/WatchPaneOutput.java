package io.github.libtmux.examples;

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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
     * Separated from {@code main} so the suite can run exactly what a reader runs. A
     * {@link Delivery.Gap} fails the watch: this reader wants every output, and a gap means some
     * was discarded.
     *
     * @return everything seen before the deadline
     */
    public static List<PaneOutput> run(Path socket, Duration watchFor, Consumer<PaneOutput> onOutput) {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        List<PaneOutput> seen = new ArrayList<>();
        try (Server server = Server.open(config)) {
            Session session = server.sessions().get(0);

            // Attaching is what makes tmux push %output at all. A client that never attaches hears
            // about command replies and nothing else.
            try (ControlClient client = server.control(session);
                    EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {
                client.send("send-keys", "-t", session.name(), "echo watched", "Enter");

                // Closing the subscription at the deadline ends the stream if the echo never comes.
                CompletableFuture.delayedExecutor(watchFor.toNanos(), TimeUnit.NANOSECONDS)
                        .execute(output::close);
                try (Stream<Delivery<PaneOutput>> steps = output.stream()) {
                    Iterator<PaneOutput> outputs = steps.map(Delivery::kept).iterator();
                    while (!sawTheEcho(seen) && outputs.hasNext()) {
                        PaneOutput arrived = outputs.next();
                        seen.add(arrived);
                        onOutput.accept(arrived);
                    }
                }
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Whether the output holds the line {@code echo} printed, not only the keys typed to run it. The
     * typed line reads {@code echo watched}, so the printed one is {@code watched} on a line of its
     * own.
     */
    public static boolean sawTheEcho(List<PaneOutput> seen) {
        String all = seen.stream().map(PaneOutput::data).reduce("", String::concat);
        return printedLine(all, "watched");
    }

    /**
     * Whether the output has a line that is exactly {@code text}, once terminal control sequences
     * are removed. A shell can put a mode switch and a carriage return in front of a printed line,
     * so the line cannot be matched as the raw bytes after a newline.
     */
    public static boolean printedLine(String output, String text) {
        return output.lines()
                .map(line -> line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").strip())
                .anyMatch(text::equals);
    }
}
