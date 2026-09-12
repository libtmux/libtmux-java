package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What a pane wait promises about time, pinned where tmux's timing can be chosen.
 *
 * <p>Against a real tmux a read takes milliseconds, so a wait that ignored its own deadline during a
 * read, or read once more after it, would pass every real-tmux case. These make the read slow, or
 * the text late, on purpose.
 */
final class PaneWaitTest {

    private static final String SEP = RowFormat.of("x").separator();

    /**
     * The defect this pins: a read ran on the server's default deadline, so a 100 ms wait over a
     * slow read took as long as the read did.
     */
    @Test
    void aSlowReadCannotStretchAShortWaitToTheServerDefault() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            tmux.captureTakes = Duration.ofSeconds(5);

            long started = System.nanoTime();
            WakeReason reason = pane.awaitText("listening", Duration.ofMillis(100));
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(WakeReason.TIMED_OUT, reason);
            assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "a 100 ms wait took " + took);
        }
    }

    /**
     * No read starts once the deadline has passed.
     *
     * <p>The text lands five milliseconds after a 120 ms deadline, which is before a poll that slept
     * a full interval past the deadline would look — and so exactly what that poll would report.
     */
    @Test
    void textThatAppearsAfterTheDeadlineIsNotReported() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            Duration timeout = Duration.ofMillis(120);
            tmux.textFrom = System.nanoTime() + timeout.plusMillis(5).toNanos();

            assertEquals(WakeReason.TIMED_OUT, pane.awaitText("listening", timeout));
        }
    }

    /**
     * An ordinary timeout does not ask tmux anything after the deadline.
     *
     * <p>The last read answered, so the server was there a poll interval ago; probing it again would
     * only spend time the caller did not give.
     */
    @Test
    void anOrdinaryTimeoutAsksTmuxNothingMore() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            int probes = tmux.liveness.get();

            assertEquals(WakeReason.TIMED_OUT, pane.awaitText("listening", Duration.ofMillis(150)));
            assertEquals(probes, tmux.liveness.get(), "a liveness probe ran after the deadline");
        }
    }

    /** Cancelling a wait is not the same answer as the wait running out. */
    @Test
    void anInterruptedWaitIsACancellationRatherThanATimeout() {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);

            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class, () -> pane.awaitText("listening", Duration.ofSeconds(5)));
            } finally {
                Thread.interrupted();
            }
        }
    }

    /** "Is it there now?" is a zero timeout, and it still gets one real read. */
    @Test
    void aZeroTimeoutStillAnswersWhatIsAlreadyThere() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            tmux.textFrom = Long.MIN_VALUE;

            assertEquals(WakeReason.SIGNALLED, pane.awaitText("listening", Duration.ZERO));
        }
    }

    /**
     * The handle a condition receives belongs to the server the caller holds.
     *
     * <p>Reads are made through a view bounded by what is left of the deadline. A handle carrying
     * that view out of the wait would give every later call on it a deadline that was nearly spent.
     */
    @Test
    void theHandleAConditionReceivesDoesNotCarryTheWaitsDeadline() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            AtomicReference<Server> seen = new AtomicReference<>();

            WakeReason reason = pane.await(
                    fresh -> {
                        seen.set(fresh.server());
                        return true;
                    },
                    Duration.ofMillis(100));

            assertEquals(WakeReason.SIGNALLED, reason);
            assertSame(server, seen.get());
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    /**
     * A tmux whose capture can be made slow or late, and which honours the deadline a request carries
     * the way the process transport does: by giving up at it.
     */
    private static final class SlowTmux implements TmuxTransport {

        private final AtomicInteger liveness = new AtomicInteger();
        private volatile Duration captureTakes = Duration.ZERO;
        private volatile long textFrom = Long.MAX_VALUE;

        Server server() {
            return Server.using(
                    ServerConfig.builder()
                            .endpoint(ServerEndpoint.namedSocket("fixture"))
                            .build(),
                    this);
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            return GroupedTmux.execute(request, 4242L, argv -> answer(argv, request.timeout()));
        }

        private CommandResult answer(List<String> argv, Duration deadline) {
            return switch (argv.get(0)) {
                case "capture-pane" -> capture(deadline);
                case "display-message" -> {
                    if (argv.size() == 3 && argv.get(2).equals("#{pid}")) {
                        liveness.incrementAndGet();
                    }
                    yield new CommandResult(0, List.of(row("4242", "3.6")), List.of());
                }
                default -> new CommandResult(0, rows(argv.get(0)), List.of());
            };
        }

        private CommandResult capture(Duration deadline) {
            if (captureTakes.compareTo(deadline) > 0) {
                pause(deadline);
                throw new TmuxTimeoutException("capture-pane outlived its deadline", null);
            }
            pause(captureTakes);
            String shown = System.nanoTime() >= textFrom ? "server listening" : "booting";
            return new CommandResult(0, List.of(shown), List.of());
        }

        private static void pause(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static List<String> rows(String command) {
            List<String> rows = new ArrayList<>();
            switch (command) {
                case "list-sessions" -> {
                    rows.add(row("$0", "alpha", "1", "1"));
                    rows.add(row("$1", "beta", "0", "2"));
                }
                case "list-windows" -> {
                    rows.add(row("$0", "@7", "0", "editor", "1", "2", "1", "80", "24", "layout"));
                    rows.add(row("$1", "@7", "3", "editor", "0", "2", "1", "80", "24", "layout"));
                    rows.add(row("$1", "@8", "4", "logs", "1", "1", "0", "80", "24", "layout"));
                }
                case "list-panes" -> {
                    rows.add(row(
                            "$0", "@7", "0", "%1", "0", "1", "nvim", "80", "24", "t", "/tmp", "11", "1", "1", "1",
                            "1"));
                    rows.add(row(
                            "$0", "@7", "0", "%2", "1", "0", "zsh", "80", "24", "t", "/tmp", "12", "1", "1", "1", "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%1", "0", "1", "nvim", "80", "24", "t", "/tmp", "11", "1", "1", "1",
                            "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%2", "1", "0", "zsh", "80", "24", "t", "/tmp", "12", "1", "1", "1", "1"));
                    rows.add(row(
                            "$1", "@8", "4", "%3", "0", "1", "tail", "80", "24", "t", "/tmp", "13", "1", "1", "1",
                            "1"));
                }
                case "list-clients" -> rows.add(row("/dev/pts/3", "$0"));
                default -> {
                    // Any other command is an operation, not a listing.
                }
            }
            return rows;
        }

        private static String row(String... fields) {
            return String.join(SEP, fields);
        }

        @Override
        public void close() {}
    }
}
