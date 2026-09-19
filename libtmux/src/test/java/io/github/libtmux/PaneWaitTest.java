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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
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
            TextOutcome reason = pane.awaitText("listening", Duration.ofMillis(100));
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(TextOutcome.TIMED_OUT, reason);
            assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "a 100 ms wait took " + took);
        }
    }

    /**
     * No read starts once the deadline has passed.
     *
     * <p>The text lands five milliseconds after a 120 ms deadline, which is before a poll that slept
     * a full interval past the deadline would look — and so exactly what that poll would report.
     *
     * <p>Timed from the wait's own first read, not from the test's clock: the wait's deadline is
     * measured from when it starts, and a loaded machine can start it late enough that a margin taken
     * from the test's clock slides inside the deadline and the text is legitimately seen.
     */
    @Test
    void textThatAppearsAfterTheDeadlineIsNotReported() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            Duration timeout = Duration.ofMillis(120);
            tmux.textAfterFirstRead = timeout.plusMillis(5);

            assertEquals(TextOutcome.TIMED_OUT, pane.awaitText("listening", timeout));
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

            assertEquals(TextOutcome.TIMED_OUT, pane.awaitText("listening", Duration.ofMillis(150)));
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

    /**
     * The two blocking waits answer a cancellation the same way.
     *
     * <p>The defect this pins: a pane wait threw {@link InterruptedException} while a channel wait
     * threw an unchecked transport failure with the flag left set, so a caller cancelling one and
     * cancelling the other had to handle two different things. The transport here does what the
     * process transport does — it cannot declare the checked exception, so it re-sets the flag and
     * reports a failure — and the channel wait has to read that as the cancellation it is.
     */
    @Test
    void aCancelledChannelWaitIsACancellationRatherThanAFailure() {
        TmuxTransport interruptible = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                Thread.currentThread().interrupt();
                throw new io.github.libtmux.transport.TmuxTransportException(
                        "interrupted before dispatch",
                        io.github.libtmux.transport.DispatchOutcome.NOT_DISPATCHED,
                        new InterruptedException());
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("fixture"))
                        .build(),
                interruptible)) {
            try {
                assertThrows(
                        InterruptedException.class,
                        () -> server.channel("ready").await(Duration.ofSeconds(5)));
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * A caller bounds one call without building a second server: every command made through the
     * view, and through the handles it hands out, carries the view's deadline.
     */
    @Test
    void aDeadlineChosenForOneCallReachesTmuxWithThatCall() {
        List<Duration> seen = new ArrayList<>();
        SlowTmux tmux = new SlowTmux() {
            @Override
            public CommandResult execute(CommandRequest request) {
                seen.add(request.timeout());
                return super.execute(request);
            }
        };
        try (Server server = tmux.server()) {
            Duration bound = Duration.ofMillis(750);

            var unused = server.within(bound).panes().get(0).capture();

            assertTrue(seen.size() >= 2, "a listing and a capture both reached tmux");
            assertTrue(seen.stream().allMatch(bound::equals), "every command carried the chosen deadline: " + seen);
            assertThrows(IllegalArgumentException.class, () -> server.within(Duration.ZERO));
        }
    }

    /**
     * The interval is the caller's to choose, and it is what a wait spends: each look is a read.
     * Counted in the fixture's own reads, over a wait with nothing to find.
     */
    @Test
    void aWaitLooksAsOftenAsItIsToldToAndNoMore() throws InterruptedException {
        SlowTmux often = new SlowTmux();
        SlowTmux rarely = new SlowTmux();
        try (Server fast = often.server();
                Server slow = rarely.server()) {
            fast.panes().get(0).awaitText("never", Duration.ofMillis(400), Duration.ofMillis(20));
            slow.panes().get(0).awaitText("never", Duration.ofMillis(400), Duration.ofMillis(200));
        }

        assertTrue(
                often.reads.get() > 2 * rarely.reads.get(),
                "20 ms looked far more than 200 ms: " + often.reads.get() + " against " + rarely.reads.get());
        assertTrue(
                rarely.reads.get() <= 4, "a 400 ms wait every 200 ms looks about three times: " + rarely.reads.get());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SlowTmux()
                        .server()
                        .panes()
                        .get(0)
                        .awaitText("x", Duration.ofSeconds(1), Duration.ofMillis(1)));
    }

    /** "Is it there now?" is a zero timeout, and it still gets one real read. */
    @Test
    void aZeroTimeoutStillAnswersWhatIsAlreadyThere() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            tmux.textFrom = Long.MIN_VALUE;

            assertEquals(TextOutcome.PRESENT_AT_ENTRY, pane.awaitText("listening", Duration.ZERO));
        }
    }

    /**
     * The defect this pins: the echo record was consulted on every look rather than once, so a wait
     * that outlived it began matching the pane's echo of the caller's own command partway through —
     * and a command slow enough to need a wait is exactly one that outlives it.
     *
     * <p>The clock here belongs to the record and moves two seconds per read, so a wait of a few
     * hundred milliseconds ages it out the way a twelve-second command does.
     */
    @Test
    void anEchoStaysDiscountedHoweverLongTheWaitRuns() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        tmux.secondsPerRead = 2;
        PaneEcho echo = new PaneEcho(tmux.recordClock::get);
        try (Server server = tmux.server(echo)) {
            Pane pane = server.panes().get(0);
            tmux.screen = List.of("$ sleep 12; echo listening");
            pane.sendLine("sleep 12; echo listening");

            assertEquals(TextOutcome.TIMED_OUT, pane.awaitText("listening", Duration.ofMillis(400)));
        }
    }

    /**
     * The defect this pins: every row holding the echo was dropped, so a command whose own output
     * names it — {@code make} answering {@code make: ... Stop.} — timed out with the answer on screen.
     */
    @Test
    void outputThatNamesTheCommandThatProducedItIsFound() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            tmux.screen = List.of("$ make");
            pane.sendLine("make");
            tmux.screenAfterReads(2, List.of("$ make", "make: *** No targets specified.  Stop."));

            assertEquals(TextOutcome.APPEARED, pane.awaitText("Stop.", Duration.ofMillis(600)));
        }
    }

    /**
     * The defect this pins: a marker left by an earlier run answered the next wait for it in
     * milliseconds. It is still reported — timing out with the text in plain sight is worse — but as
     * what it is.
     */
    @Test
    void textLeftOnScreenByAnEarlierRunIsNotReportedAsHavingAppeared() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            tmux.screen = List.of("$ echo listening", "listening", "$ ");

            assertEquals(TextOutcome.PRESENT_AT_ENTRY, pane.awaitText("listening", Duration.ofMillis(200)));
        }
    }

    /** Keys typed as text are echoed like any other text, whichever call sent them. */
    @Test
    void textSentWithoutASubmittingKeyIsStillAnEcho() throws InterruptedException {
        SlowTmux tmux = new SlowTmux();
        try (Server server = tmux.server()) {
            Pane pane = server.panes().get(0);
            tmux.screen = List.of("$ sleep 4; echo listening");
            pane.send("sleep 4; echo listening");
            pane.send("Enter");

            assertEquals(TextOutcome.TIMED_OUT, pane.awaitText("listening", Duration.ofMillis(200)));
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
    private static class SlowTmux implements TmuxTransport {

        private final AtomicInteger liveness = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicLong recordClock = new AtomicLong();
        private volatile Duration captureTakes = Duration.ZERO;
        private volatile long textFrom = Long.MAX_VALUE;
        private volatile @Nullable Duration textAfterFirstRead;
        private volatile long firstRead = Long.MIN_VALUE;
        private volatile int secondsPerRead;
        private volatile @Nullable List<String> screen;
        private volatile int screenChangesAfter = Integer.MAX_VALUE;
        private volatile List<String> screenAfterwards = List.of();

        /** What the pane shows once it has been read this many times, so output can arrive mid-wait. */
        void screenAfterReads(int reads, List<String> lines) {
            screenChangesAfter = reads;
            screenAfterwards = lines;
        }

        Server server() {
            return server(new PaneEcho());
        }

        Server server(PaneEcho echo) {
            return Server.using(
                    ServerConfig.builder()
                            .endpoint(ServerEndpoint.namedSocket("fixture"))
                            .build(),
                    this,
                    echo);
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
            int read = reads.incrementAndGet();
            recordClock.addAndGet(Duration.ofSeconds(secondsPerRead).toNanos());
            List<String> scripted = screen;
            if (scripted != null) {
                return new CommandResult(0, read > screenChangesAfter ? screenAfterwards : scripted, List.of());
            }
            long now = System.nanoTime();
            if (firstRead == Long.MIN_VALUE) {
                firstRead = now;
            }
            Duration after = textAfterFirstRead;
            boolean arrived = after == null ? now >= textFrom : now - firstRead >= after.toNanos();
            String shown = arrived ? "server listening" : "booting";
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
                            "$0", "@7", "0", "%1", "0", "1", "nvim", "80", "24", "0", "0", "t", "/tmp", "11", "1", "1",
                            "1", "1"));
                    rows.add(row(
                            "$0", "@7", "0", "%2", "1", "0", "zsh", "80", "24", "0", "0", "t", "/tmp", "12", "1", "1",
                            "1", "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%1", "0", "1", "nvim", "80", "24", "0", "0", "t", "/tmp", "11", "1", "1",
                            "1", "1"));
                    rows.add(row(
                            "$1", "@7", "3", "%2", "1", "0", "zsh", "80", "24", "0", "0", "t", "/tmp", "12", "1", "1",
                            "1", "1"));
                    rows.add(row(
                            "$1", "@8", "4", "%3", "0", "1", "tail", "80", "24", "0", "0", "t", "/tmp", "13", "1", "1",
                            "1", "1"));
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
