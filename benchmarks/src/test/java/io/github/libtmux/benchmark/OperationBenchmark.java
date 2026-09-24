package io.github.libtmux.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.CommandChain;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;
import io.github.libtmux.batch.Batch;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.Delivery;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutput;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures what an operation costs, and writes the table the docs show.
 *
 * <p>Tagged {@code benchmark} and excluded from the ordinary suite and the release matrix: it takes
 * seconds rather than milliseconds and it writes a file. Run it with
 * {@code ./gradlew operationBenchmark}.
 *
 * <p>Numbers are never written by hand. This regenerates {@code docs/benchmarks/operations.md} from
 * a run on the tmux it is given, and stamps which tmux that was, because a table without its
 * conditions is a claim rather than a measurement.
 */
@Tag("benchmark")
final class OperationBenchmark {

    private static final int ROUNDS = 20;

    /** The tmux the measurements ran against, asked of the running server rather than inferred. */
    private String tmux = "";

    /** Counts what the transport really did, which is the only honest way to report process cost. */
    private static final class Counting implements TmuxTransport {

        private final TmuxTransport delegate;
        private final AtomicInteger dispatches = new AtomicInteger();

        Counting(TmuxTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            dispatches.incrementAndGet();
            return delegate.execute(request);
        }

        @Override
        public CommandResult executeWaiting(CommandRequest request) {
            dispatches.incrementAndGet();
            return delegate.executeWaiting(request);
        }

        @Override
        public String realm() {
            return delegate.realm();
        }

        // A control client is one long-lived process, not a dispatch, so it is not counted.
        @Override
        public java.util.Optional<io.github.libtmux.transport.ControlCarrier> controlCarrier() {
            return delegate.controlCarrier();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record Measured(String label, long millis, long low, long high, int dispatches, String output) {}

    /** One run of a scenario, before the samples are taken together. */
    private record Sample(long millis, int dispatches, String output) {}

    @Test
    void writeTheOperationTable(@TempDir Path directory) throws Exception {
        List<Measured> grouping = List.of(
                measure(directory, "one-at-a-time", server -> {}, OperationBenchmark::create),
                measure(directory, "batch", server -> {}, OperationBenchmark::createBatched),
                measure(directory, "chain", server -> {}, OperationBenchmark::createChained));

        List<Measured> reading = List.of(
                measure(directory, "traversal", OperationBenchmark::plantWindows, OperationBenchmark::traverse),
                measure(directory, "snapshot", OperationBenchmark::plantWindows, OperationBenchmark::snapshot));

        List<Measured> narrowing = List.of(
                measure(
                        directory,
                        "snapshot() then find",
                        OperationBenchmark::plantSessions,
                        OperationBenchmark::findInSnapshot),
                measure(
                        directory,
                        "session(name)",
                        OperationBenchmark::plantSessions,
                        OperationBenchmark::sessionByName),
                measure(
                        directory,
                        "sessions(filter), two match",
                        OperationBenchmark::plantSessions,
                        OperationBenchmark::filtered));

        List<Measured> guarding = List.of(
                measure(directory, "unguarded", OperationBenchmark::plantWindows, OperationBenchmark::unguarded),
                measure(directory, "guarded", OperationBenchmark::plantWindows, OperationBenchmark::guarded));

        List<Measured> waits = List.of(
                measure(directory, "poll: awaitText", OperationBenchmark::plainShell, OperationBenchmark::poll),
                measure(directory, "push: control %output", OperationBenchmark::plainShell, OperationBenchmark::push),
                measure(directory, "signal: Pane.run", OperationBenchmark::plainShell, OperationBenchmark::signal));

        List<Measured> options = List.of(
                measure(directory, "one option", server -> {}, OperationBenchmark::oneOption),
                measure(directory, "all()", server -> {}, OperationBenchmark::allOptions),
                measure(directory, "effective()", server -> {}, OperationBenchmark::effectiveOptions));

        assertEquals(
                1,
                grouping.stream().map(Measured::output).distinct().count(),
                "grouping the same commands built something different: " + labelled(grouping));

        // Told where to write rather than guessing from a working directory, which for a Gradle
        // Test task is the module and not the root.
        Path report = Path.of(System.getProperty("libtmux.benchmark.out", "build/operations.md"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(grouping, reading, narrowing, guarding, waits, options));

        assertTrue(Files.exists(report), "the benchmark wrote no table");
    }

    // ------------------------------------------------------------------------------- scenarios

    /** How many times each wait runs, each on a command that prints after {@link #DELAY_MILLIS}. */
    private static final int WAIT_ROUNDS = 5;

    private static final int DELAY_MILLIS = 200;

    /** A plain sh with a two-character prompt, drawn before the clock starts. */
    private static void plainShell(Server server) {
        Pane pane = server.newSession(session -> session.named("wait").running("env", "PS1=$ ", "ENV=", "/bin/sh"))
                .activePane()
                .orElseThrow();
        try {
            pane.awaitText("$", Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Pane waiting(Server server) {
        return server.session("wait").orElseThrow().activePane().orElseThrow();
    }

    /** The command each wait waits for: prints its marker after a fixed delay, never in its echo. */
    private static String printsAfterDelay(int round) {
        return "sleep " + (DELAY_MILLIS / 1000.0) + "; printf 'mark-%s\\n' " + round;
    }

    /** Reads the screen on a timer until the marker is there. */
    private static String poll(Server server) {
        Pane pane = waiting(server);
        for (int round = 0; round < WAIT_ROUNDS; round++) {
            pane.sendLine(printsAfterDelay(round));
            try {
                pane.awaitText("mark-" + round, Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return "";
    }

    /** Is told: a control client attached for the whole run, reading the output tmux pushes. */
    private static String push(Server server) {
        Pane pane = waiting(server);
        try (ControlClient client = server.control(server.session("wait").orElseThrow());
                EventSubscription<PaneOutput> output = client.subscribeOutput(1024)) {
            for (int round = 0; round < WAIT_ROUNDS; round++) {
                pane.sendLine(printsAfterDelay(round));
                StringBuilder seen = new StringBuilder();
                while (seen.indexOf("mark-" + round) < 0) {
                    seen.append(
                            output.next(Duration.ofSeconds(10)).orElseThrow()
                                            instanceof Delivery.Event<PaneOutput> event
                                    ? event.value().data()
                                    : "");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "";
    }

    /** Waits on the command itself: tmux blocks until the command's exit trap signals. */
    private static String signal(Server server) {
        Pane pane = waiting(server);
        for (int round = 0; round < WAIT_ROUNDS; round++) {
            try {
                pane.run(printsAfterDelay(round), Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return "";
    }

    /** Gives the readers something to find, before the clock starts. */
    private static void plantWindows(Server server) {
        Session session = server.sessions().get(0);
        for (int index = 0; index < 3; index++) {
            String name = "bench-" + index;
            session.newWindow(window -> window.named(name).detached());
        }
    }

    private static void plantSessions(Server server) {
        for (int index = 0; index < 5; index++) {
            server.newSession("bench-" + index);
        }
    }

    /** One session found by reading everything and looking. */
    private static String findInSnapshot(Server server) {
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.snapshot().session("bench-3").orElseThrow().name();
        }
        return seen;
    }

    /** The same session asked for by name, which lists only that session. */
    private static String sessionByName(Server server) {
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.session("bench-3").orElseThrow().name();
        }
        return seen;
    }

    /** A filter tmux applies, matching two sessions. */
    private static String filtered(Server server) {
        int seen = 0;
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.sessions(Session_.name().in(List.of("bench-1", "bench-3")))
                    .size();
        }
        return Integer.toString(seen);
    }

    /** Builds a workspace one call at a time: what a program setting tmux up does naively. */
    private static String create(Server server) {
        Session session = server.sessions().get(0);
        for (int round = 0; round < ROUNDS; round++) {
            String name = "bench-" + round;
            session.newWindow(window -> window.named(name).detached());
        }
        return Integer.toString(session.refresh().windows().size());
    }

    /** The same workspace, asked for in one request. */
    private static String createBatched(Server server) {
        Session session = server.sessions().get(0);
        Batch batch = server.batch();
        for (int round = 0; round < ROUNDS; round++) {
            batch.add("new-window", "-d", "-n", "bench-" + round);
        }
        batch.run();
        return Integer.toString(session.refresh().windows().size());
    }

    /** The same workspace again, as steps that each act on what the last one made. */
    private static String createChained(Server server) {
        Session session = server.sessions().get(0);
        CommandChain chain = server.chain();
        for (int round = 0; round < ROUNDS; round++) {
            chain.newWindow("bench-" + round);
        }
        chain.run();
        return Integer.toString(session.refresh().windows().size());
    }

    /** Reads the hierarchy repeatedly through handles: what a program watching tmux does. */
    private static String traverse(Server server) {
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.windows().stream()
                    .filter(Window_.name().startsWith("bench"))
                    .map(Window::name)
                    .sorted()
                    .toList()
                    .toString();
        }
        return seen;
    }

    /** The same reads, taken as one strict capture each time. */
    private static String snapshot(Server server) {
        int seen = 0;
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.snapshot().windows().size();
        }
        return Integer.toString(seen);
    }

    /** A command that reaches tmux directly, with nothing fencing it. */
    private static String unguarded(Server server) {
        // Taken and discarded, so both rows pay for the same handle and the difference is the guard.
        server.sessions().get(0);
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.expand("#{session_name}");
        }
        return seen;
    }

    /** The same command through a handle, which wraps it in the staleness guard. */
    private static String guarded(Server server) {
        Session session = server.sessions().get(0);
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = session.expand("#{session_name}");
        }
        return seen;
    }

    /** One option read by name, which is one request. */
    private static String oneOption(Server server) {
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.globalOptions().get("status-left").orElse("");
        }
        return seen;
    }

    /** Every option this scope sets: names from the listing, then values. */
    private static String allOptions(Server server) {
        int seen = 0;
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.globalOptions().all().size();
        }
        return Integer.toString(seen);
    }

    /** Every option in effect, which is the widest listing a scope has. */
    private static String effectiveOptions(Server server) {
        int seen = 0;
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.globalOptions().effective().size();
        }
        return Integer.toString(seen);
    }

    // ------------------------------------------------------------------------------- measuring

    /**
     * Runs a scenario this many times and reports the middle one with the spread either side.
     *
     * <p>One run is a number, not a measurement: a fresh server, a cold page cache and whatever else
     * the machine is doing all land in it. Three says whether the number is worth reading, and the
     * dispatch count has to be the same every time or the scenario is not doing the same work.
     */
    private static final int SAMPLES = 3;

    private Measured measure(Path root, String scenario, Consumer<Server> setUp, Function<Server, String> work)
            throws IOException {
        List<Long> timings = new ArrayList<>(SAMPLES);
        String output = "";
        int dispatches = -1;
        for (int sample = 0; sample < SAMPLES; sample++) {
            Sample taken = once(root, scenario, sample, setUp, work);
            timings.add(taken.millis());
            output = taken.output();
            if (dispatches < 0) {
                dispatches = taken.dispatches();
            } else if (taken.dispatches() != dispatches) {
                throw new AssertionError(
                        "%s dispatched %d commands on one run and %d on another, so these rows would be comparing different work"
                                .formatted(scenario, taken.dispatches(), dispatches));
            }
        }
        List<Long> sorted = timings.stream().sorted().toList();
        return new Measured(
                scenario, sorted.get(sorted.size() / 2), sorted.getFirst(), sorted.getLast(), dispatches, output);
    }

    Sample once(Path root, String scenario, int sample, Consumer<Server> setUp, Function<Server, String> work)
            throws IOException {
        Path home = root.resolve(scenario.replace("()", "").replace(' ', '-') + "-" + sample);
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        ServerConfig built = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .defaultTimeout(Duration.ofSeconds(30))
                .build();

        Counting counting = new Counting(new ProcessTransport());
        try (Server server = Server.using(built, counting)) {
            try {
                server.newSession("bench");
                tmux = server.version().toString();
                setUp.accept(server);
                // Warm: the first command pays for starting a server, which is not what is being
                // compared. Whatever the scenario needed is already in place, so none of it is timed.
                server.windows();
                int before = counting.dispatches.get();
                long started = System.nanoTime();
                String output = work.apply(server);
                long millis = (System.nanoTime() - started) / 1_000_000;
                int dispatches = counting.dispatches.get() - before;
                return new Sample(millis, dispatches, output);
            } finally {
                // Closing leaves tmux running, and the socket goes with the temporary directory,
                // so a scenario that throws would leave a daemon nothing can reach.
                server.killServer();
            }
        } finally {
            counting.close();
        }
    }

    // --------------------------------------------------------------------------------- the table

    private String render(
            List<Measured> grouping,
            List<Measured> reading,
            List<Measured> narrowing,
            List<Measured> guarding,
            List<Measured> waits,
            List<Measured> options) {
        StringBuilder out = new StringBuilder();
        out.append("# What an operation costs, measured\n\n")
                .append("Regenerated by `./gradlew operationBenchmark`. Never edit by hand.\n\n")
                .append("Measured against tmux `")
                .append(tmux)
                .append("`, ")
                .append(ROUNDS)
                .append(" rounds per scenario, each scenario run ")
                .append(SAMPLES)
                .append(" times on a fresh server. A wall clock shows the middle run, and the fastest ")
                .append("and slowest either side of it where they differ, so a row that moved is not ")
                .append("read as a row that means something. Each `ProcessTransport` dispatch starts a ")
                .append("tmux process, so the dispatch count is the cost and the milliseconds are one ")
                .append("machine's rendering of it; the count is identical across the runs or the table ")
                .append("is not written at all. Read the shape.\n\n");

        out.append("## Collapsing round trips\n\n")
                .append("The same ")
                .append(ROUNDS)
                .append(" windows, asked for three ways. This is the whole of the answer to ")
                .append("per-command process cost, so it leads.\n\n");
        table(out, "strategy", grouping);
        out.append("\nEvery row pays the same four commands for the handle it starts from and the ")
                .append("count it ends with, so the ratio between them understates what grouping ")
                .append("saves: the work itself is 60 commands against one.\n");

        out.append("\n## Reading the hierarchy\n\n")
                .append("`windows()` and `snapshot()` both throw when capture fails. They read who the ")
                .append("server is, then run the four listings as one group fenced against that ")
                .append("answer. Two commands, whatever the hierarchy holds.\n\n");
        table(out, "read", reading);

        out.append("\n## Narrow reads\n\n")
                .append("Five sessions, one wanted. A lookup by name reads who the server is, then ")
                .append("lists only that session, its windows, and its panes as one fenced group: two ")
                .append("commands, the same as a snapshot, over less of the server. A filter tmux can ")
                .append("apply adds one probe for which sessions match, then reads all of them in one ")
                .append("more group: three commands, however many match.\n\n");
        table(out, "read", narrowing);

        out.append("\n## What the staleness guard costs\n\n")
                .append("A handle fences every command it sends behind `if-shell -F`, so that a ")
                .append("handle cannot act on a tmux that replaced the one it was taken from. Both ")
                .append("rows take a handle first, so the difference is the guard alone.\n\n");
        table(out, "command", guarding);
        out.append("\nThe guard rides inside the one command it fences, so it costs no further ")
                .append("process. What it adds is bytes, against the 16384 a tmux command may carry.\n");

        out.append("\n## Push against poll\n\n")
                .append("The same command, waited on three ways, ")
                .append(WAIT_ROUNDS)
                .append(" times: it prints a marker ")
                .append(DELAY_MILLIS)
                .append(" ms after it starts, so ")
                .append(WAIT_ROUNDS * DELAY_MILLIS)
                .append(" ms of every row is the command itself. What is left is what the wait ")
                .append("added: noticing late, and the processes spent looking.\n\n");
        out.append("| wait | wall clock | added per wait | commands dispatched |\n")
                .append("| --- | --- | --- | --- |\n");
        for (Measured row : waits) {
            long added = Math.max(0, row.millis() - (long) WAIT_ROUNDS * DELAY_MILLIS) / WAIT_ROUNDS;
            out.append("| `%s` | %s | %d ms | %d |%n".formatted(row.label(), spread(row), added, row.dispatches()));
        }
        out.append("\nThree different costs, not one ranking. A poll spends a tmux process every 50 ms, ")
                .append("so its count grows with how long it waits, and it notices up to one interval ")
                .append("late. A push pays once to attach a control client — not in the count, and most ")
                .append("of its added time over so few waits — and after that is told as output arrives, ")
                .append("so its count is only the commands typed. `Pane.run` pays a fixed handful per ")
                .append("command whatever the command's length: reading the pane, the wait, reading the ")
                .append("output back, and the three tmux calls the pane's shell makes to report the end. ")
                .append("In return it is the only one of the three that knows the command ended, and ")
                .append("with what status.\n");

        out.append("\n## What an option listing costs\n\n")
                .append("A listed value is escaped for display, and how it is escaped changes ")
                .append("between releases, so names come from the listing and values from ")
                .append("`show-options -v`, batched.\n\n");
        table(out, "read", options);
        out.append("\nThat is the cost: one option is one command, and a listing is two whatever ")
                .append("its size, until it outgrows what one command may carry.\n");
        return out.toString();
    }

    private static void table(StringBuilder out, String heading, List<Measured> rows) {
        out.append("| %s | wall clock | commands dispatched |%n".formatted(heading))
                .append("| --- | --- | --- |\n");
        for (Measured row : rows) {
            out.append("| `%s` | %s | %d |%n".formatted(row.label(), spread(row), row.dispatches()));
        }
    }

    /** The middle run, and the fastest and slowest either side of it when they differ. */
    private static String spread(Measured row) {
        return row.low() == row.high()
                ? "%d ms".formatted(row.millis())
                : "%d ms (%d-%d)".formatted(row.millis(), row.low(), row.high());
    }

    private static List<String> labelled(List<Measured> rows) {
        List<String> described = new ArrayList<>(rows.size());
        rows.forEach(row -> described.add(row.label() + "=" + row.output()));
        return described;
    }
}
