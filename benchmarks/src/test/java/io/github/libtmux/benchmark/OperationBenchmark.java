package io.github.libtmux.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.CommandChain;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;
import io.github.libtmux.batch.Batch;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlReply;
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
import java.util.Arrays;
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

    /** How many commands each transport sends when compared one at a time. */
    private static final int TRANSPORT_ROUNDS = 200;

    /** Sent first and not timed, so the JIT and tmux's own caches settle before a round counts. */
    private static final int TRANSPORT_WARMUP = 50;

    /** The read both transports send: identical argv, so the only difference is how it travels. */
    private static final List<String> TRANSPORT_PROBE = List.of("display-message", "-p", "#{session_name}");

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

    /** One transport's cost for a single command, timed individually rather than as a group. */
    private record Timed(String label, long medianNanos, long p95Nanos, int commands) {}

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
                        OperationBenchmark::filtered),
                measure(
                        directory,
                        "snapshot() then find pane",
                        OperationBenchmark::plantSessions,
                        server -> findPaneInSnapshot(server, FIFTH_PANE)),
                measure(
                        directory,
                        "pane(id)",
                        OperationBenchmark::plantSessions,
                        server -> paneById(server, FIFTH_PANE)));

        List<Measured> narrowingMany = List.of(
                measure(
                        directory,
                        "snapshot() then find (50)",
                        OperationBenchmark::plantManySessions,
                        OperationBenchmark::findInSnapshot),
                measure(
                        directory,
                        "session(name) (50)",
                        OperationBenchmark::plantManySessions,
                        OperationBenchmark::sessionByName),
                measure(
                        directory,
                        "sessions(filter), two match (50)",
                        OperationBenchmark::plantManySessions,
                        OperationBenchmark::filtered),
                measure(
                        directory,
                        "snapshot() then find pane (50)",
                        OperationBenchmark::plantManySessions,
                        server -> findPaneInSnapshot(server, FIFTH_PANE)),
                measure(
                        directory,
                        "pane(id) (50)",
                        OperationBenchmark::plantManySessions,
                        server -> paneById(server, FIFTH_PANE)));

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

        Timed process = measureProcessPerCommand(directory);
        Timed control = measureControlPerCommand(directory);

        assertEquals(
                1,
                grouping.stream().map(Measured::output).distinct().count(),
                "grouping the same commands built something different: " + labelled(grouping));

        // The measurement behind keeping the process transport the default: a control client
        // answering in place has to actually beat spawning tmux, not merely differ from it.
        assertTrue(
                control.medianNanos() * 2 < process.medianNanos(),
                "control did not clearly beat a process per command: control=%dns process=%dns"
                        .formatted(control.medianNanos(), process.medianNanos()));

        // Told where to write rather than guessing from a working directory, which for a Gradle
        // Test task is the module and not the root.
        Path report = Path.of(System.getProperty("libtmux.benchmark.out", "build/operations.md"));
        Files.createDirectories(report.getParent());
        Files.writeString(
                report,
                render(
                        grouping,
                        reading,
                        narrowing,
                        narrowingMany,
                        guarding,
                        waits,
                        options,
                        List.of(process, control)));

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

    /** Fifty sessions of three windows, so a whole-server read has something to pay for. */
    private static void plantManySessions(Server server) {
        for (int index = 0; index < 50; index++) {
            Session session = server.newSession("bench-" + index);
            session.newWindow(window -> window.detached());
            session.newWindow(window -> window.detached());
        }
    }

    /** A pane both plantings create, in a session other than the first. */
    private static final PaneId FIFTH_PANE = new PaneId("%4");

    /** One pane found by reading everything and looking. */
    private static String findPaneInSnapshot(Server server, PaneId id) {
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.snapshot().panes().stream()
                    .filter(pane -> pane.id().equals(id))
                    .findFirst()
                    .orElseThrow()
                    .id()
                    .value();
        }
        return seen;
    }

    /** The same pane asked for by id, which reads only the session holding it. */
    private static String paneById(Server server, PaneId id) {
        String seen = "";
        for (int round = 0; round < ROUNDS; round++) {
            seen = server.pane(id).orElseThrow().id().value();
        }
        return seen;
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
        ServerConfig built = configFor(root.resolve(scenario.replace("()", "").replace(' ', '-') + "-" + sample));
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

    /** One scenario's isolated server: its own socket and an empty configuration, under {@code home}. */
    private static ServerConfig configFor(Path home) throws IOException {
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        return ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .defaultTimeout(Duration.ofSeconds(30))
                .build();
    }

    // -------------------------------------------------------------------- control vs. process

    /**
     * Sends {@link #TRANSPORT_PROBE} {@link #TRANSPORT_ROUNDS} times as a fresh tmux process each,
     * timing each dispatch on its own so the spread is a distribution rather than a single total.
     */
    private Timed measureProcessPerCommand(Path root) throws IOException {
        try (Server server = Server.using(configFor(root.resolve("transport-process")), new ProcessTransport())) {
            try {
                server.newSession("bench");
                server.windows();
                for (int round = 0; round < TRANSPORT_WARMUP; round++) {
                    if (!server.cmd(TRANSPORT_PROBE).succeeded()) {
                        throw new AssertionError("process warmup failed");
                    }
                }
                long[] nanos = new long[TRANSPORT_ROUNDS];
                for (int round = 0; round < TRANSPORT_ROUNDS; round++) {
                    long started = System.nanoTime();
                    CommandResult result = server.cmd(TRANSPORT_PROBE);
                    nanos[round] = System.nanoTime() - started;
                    if (!result.succeeded()) {
                        throw new AssertionError("process probe failed: " + result);
                    }
                }
                return new Timed("process: one command", median(nanos), percentile95(nanos), TRANSPORT_ROUNDS);
            } finally {
                server.killServer();
            }
        }
    }

    /**
     * Sends {@link #TRANSPORT_PROBE} {@link #TRANSPORT_ROUNDS} times over one attached
     * {@link ControlClient}, opened before timing starts so the attach itself is not counted.
     */
    private Timed measureControlPerCommand(Path root) throws IOException {
        try (Server server = Server.using(configFor(root.resolve("transport-control")), new ProcessTransport())) {
            try {
                Session session = server.newSession("bench");
                server.windows();
                try (ControlClient client = server.control(session)) {
                    for (int round = 0; round < TRANSPORT_WARMUP; round++) {
                        if (!client.send(TRANSPORT_PROBE).succeeded()) {
                            throw new AssertionError("control warmup failed");
                        }
                    }
                    long[] nanos = new long[TRANSPORT_ROUNDS];
                    for (int round = 0; round < TRANSPORT_ROUNDS; round++) {
                        long started = System.nanoTime();
                        ControlReply reply = client.send(TRANSPORT_PROBE);
                        nanos[round] = System.nanoTime() - started;
                        if (!reply.succeeded()) {
                            throw new AssertionError("control probe failed: " + reply);
                        }
                    }
                    return new Timed(
                            "control: one command (attached)", median(nanos), percentile95(nanos), TRANSPORT_ROUNDS);
                }
            } finally {
                server.killServer();
            }
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long percentile95(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1));
        return sorted[index];
    }

    // --------------------------------------------------------------------------------- the table

    private String render(
            List<Measured> grouping,
            List<Measured> reading,
            List<Measured> narrowing,
            List<Measured> narrowingMany,
            List<Measured> guarding,
            List<Measured> waits,
            List<Measured> options,
            List<Timed> transports) {
        StringBuilder out = new StringBuilder();
        out.append("# What an operation costs, measured\n\n")
                .append("Regenerated by `./gradlew operationBenchmark`. Never edit by hand.\n\n");

        out.append("| environment | value |\n")
                .append("| --- | --- |\n")
                .append("| tmux | `")
                .append(tmux)
                .append("` |\n| JVM | `")
                .append(jvm())
                .append("` |\n| OS | `")
                .append(os())
                .append("` |\n| CPU | `")
                .append(cpu())
                .append("` |\n\n");

        out.append("Measured ")
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
                .append("Five sessions, one wanted. A lookup by name, by pane id, or by a filter tmux can ")
                .append("apply reads who the server is, then lists only the sessions it wants, with their ")
                .append("windows and panes, as one fenced group: two commands, the same as a snapshot, ")
                .append("over less of the server. A pane or window condition is looped over each ")
                .append("session inside tmux, so no probe comes first.\n\n");
        table(out, "read", narrowing);
        out.append("\nThe same reads against fifty sessions of three windows each. The commands do not ")
                .append("change; what a whole-server read pays for is the rows.\n\n");
        table(out, "read", narrowingMany);

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

        out.append("\n## One command, two transports\n\n")
                .append("The same read, `display-message -p \"#{session_name}\"`, sent ")
                .append(TRANSPORT_ROUNDS)
                .append(" times after ")
                .append(TRANSPORT_WARMUP)
                .append(" untimed: once as a `ProcessTransport` dispatch, a fresh tmux process per ")
                .append("command, and once as a request over an attached `ControlClient`, which stays ")
                .append("connected between requests. Nanoseconds, because that is the size of the gap ")
                .append("a persistent control-mode transport would close.\n\n");
        out.append("| transport | median per command | p95 per command | commands |\n")
                .append("| --- | --- | --- | --- |\n");
        for (Timed row : transports) {
            out.append("| `%s` | %s | %s | %d |%n"
                    .formatted(row.label(), micros(row.medianNanos()), micros(row.p95Nanos()), row.commands()));
        }
        out.append("\nThis justifies keeping the process transport the default and control opt-in; it ")
                .append("does not justify making a persistent control transport the default, and it does ")
                .append("not offset what control mode gives up to get there. A control reply is an ")
                .append("acknowledgement, not a completion — a queued `run-shell` finishes later, off ")
                .append("this measurement. Standard input has no per-command channel in control mode, so ")
                .append("`Pane.paste` still needs a process. One control client answers one request at a ")
                .append("time, so concurrent callers serialize behind it, where the process transport ")
                .append("runs them at once. An untargeted command sent over control resolves against the ")
                .append("attached session, not whichever session a caller meant.\n");

        out.append("\n## What this does not measure\n\n")
                .append("Not measured here: MCP tool call overhead, and FS2 stream throughput through the ")
                .append("Cats Effect facade. Neither has an existing harness to extend — this file times a ")
                .append("`Server` against real tmux, not a running MCP session or a bounded stream — and ")
                .append("building one is its own project rather than an addition to this one.\n");
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

    private static String micros(long nanos) {
        return "%.1f µs".formatted(nanos / 1000.0);
    }

    // ----------------------------------------------------------------------------- environment

    private static String jvm() {
        return System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    }

    private static String os() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                + System.getProperty("os.arch") + ")";
    }

    private static String cpu() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cpuModel().orElse("unknown model") + ", " + cores + (cores == 1 ? " core" : " cores");
    }

    /** Linux only: the one fact {@code Runtime} does not report about the CPU this ran on. */
    private static java.util.Optional<String> cpuModel() {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuinfo)) {
            return java.util.Optional.empty();
        }
        try (var lines = Files.lines(cpuinfo)) {
            return lines.filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .findFirst();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static List<String> labelled(List<Measured> rows) {
        List<String> described = new ArrayList<>(rows.size());
        rows.forEach(row -> described.add(row.label() + "=" + row.output()));
        return described;
    }
}
