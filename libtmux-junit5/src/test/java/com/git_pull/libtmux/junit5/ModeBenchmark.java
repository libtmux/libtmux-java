package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.CommandChain;
import com.git_pull.libtmux.ExecutionMode;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.ServerEndpoint;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.Window_;
import com.git_pull.libtmux.batch.Batch;
import com.git_pull.libtmux.transport.CommandRequest;
import com.git_pull.libtmux.transport.CommandResult;
import com.git_pull.libtmux.transport.ControlTransport;
import com.git_pull.libtmux.transport.ProcessTransport;
import com.git_pull.libtmux.transport.TmuxTransport;
import com.git_pull.libtmux.transport.VirtualThreadTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures what each execution mode costs, and writes the table the docs show.
 *
 * <p>Tagged {@code benchmark} and excluded from the ordinary suite and the release matrix: it takes
 * seconds rather than milliseconds and it writes a file. Run it with {@code ./gradlew modeBenchmark}.
 *
 * <p>Numbers are never written by hand. This regenerates {@code docs/benchmarks/modes.md} from a run
 * on the tmux it is given, and stamps which tmux that was, because a table without its conditions is
 * a claim rather than a measurement.
 */
@Tag("benchmark")
final class ModeBenchmark {

    private static final int ROUNDS = 20;

    /**
     * The tmux the measurements ran against, asked of the running server rather than inferred.
     *
     * <p>A path would name a build without proving which one answered, and would put whichever
     * machine measured last into a shipped document.
     */
    private String tmux = "";

    /** Counts what a carrier really did, which is the only honest way to report process cost. */
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
        public String realm() {
            return delegate.realm();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * One measurement of one scenario.
     *
     * @param label the carrier, and the strategy too where a section compares them
     * @param processes tmux processes actually started, counted rather than assumed
     * @param viaClient whether a control client carried what the processes did not
     */
    private record Measured(
            String label, long millis, int dispatches, int processes, boolean viaClient, String output) {}

    @Test
    void writeTheModeTable(@TempDir Path directory) throws Exception {
        List<Measured> traversal = new ArrayList<>();
        List<Measured> creation = new ArrayList<>();
        List<Measured> grouping = new ArrayList<>();
        Map<String, String> sameQuery = new LinkedHashMap<>();

        for (ExecutionMode mode : ExecutionMode.values()) {
            Measured read = measure(directory, mode, "traversal", ModeBenchmark::plantWindows, ModeBenchmark::traverse);
            traversal.add(read);
            Measured oneAtATime = measure(directory, mode, "creation", server -> {}, ModeBenchmark::create);
            creation.add(oneAtATime);
            grouping.add(relabelled(oneAtATime, mode + ", one call at a time"));
            grouping.add(relabelled(
                    measure(directory, mode, "batch", server -> {}, ModeBenchmark::createBatched), mode + ", batch()"));
            grouping.add(relabelled(
                    measure(directory, mode, "chain", server -> {}, ModeBenchmark::createChained), mode + ", chain()"));
            // The traversal scenario is the one whose work *is* the query below, so its output is
            // what the table may show. Taking creation's would have labelled a window count as a
            // filter result.
            sameQuery.put(mode.name(), read.output());
        }

        assertEquals(
                1, Set.copyOf(sameQuery.values()).size(), "the modes disagreed about the query result: " + sameQuery);
        assertEquals(
                1,
                grouping.stream().map(Measured::output).distinct().count(),
                "grouping the same commands built something different: "
                        + grouping.stream()
                                .map(row -> row.label() + "=" + row.output())
                                .toList());

        // Told where to write rather than guessing from a working directory, which for a Gradle
        // Test task is the module and not the root.
        Path report = Path.of(System.getProperty("libtmux.benchmark.out", "build/modes.md"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(traversal, creation, grouping, sameQuery));

        assertTrue(Files.exists(report), "the benchmark wrote no table");
    }

    // ------------------------------------------------------------------------------- scenarios

    /** Gives the traversal something to find, before the clock starts. */
    private static void plantWindows(Server server) {
        Session session = server.sessions().get(0);
        for (int i = 0; i < 3; i++) {
            String name = "bench-" + i;
            session.newWindow(w -> w.named(name).detached());
        }
    }

    /** Reads the hierarchy repeatedly: what a program watching tmux does. */
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

    /** Builds a workspace: what a program setting tmux up does. */
    private static String create(Server server) {
        Session session = server.sessions().get(0);
        for (int round = 0; round < ROUNDS; round++) {
            String name = "bench-" + round;
            session.newWindow(w -> w.named(name).detached());
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

    /** The same measurement under a name that says which strategy produced it. */
    private static Measured relabelled(Measured measured, String label) {
        return new Measured(
                label,
                measured.millis(),
                measured.dispatches(),
                measured.processes(),
                measured.viaClient(),
                measured.output());
    }

    // ------------------------------------------------------------------------------- measuring

    private Measured measure(
            Path root, ExecutionMode mode, String scenario, Consumer<Server> setUp, Function<Server, String> work)
            throws IOException {
        Path home = root.resolve(mode.name() + "-" + scenario);
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        ServerConfig built = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .defaultTimeout(Duration.ofSeconds(30))
                .build();

        // Two counters, because a carrier that falls back does not say so. The inner one is the only
        // thing that ever starts a process, so counting there reports what was really spent rather
        // than what the mode implies — which is how a command group under CONTROL, carried by a
        // process because a control client frames one reply per command, shows up as the process it
        // is instead of hiding behind "one client, reused".
        Counting processes = new Counting(new ProcessTransport());
        TmuxTransport carrier =
                switch (mode) {
                    case DIRECT -> processes;
                    case CONTROL -> new ControlTransport(built, processes);
                    case VIRTUAL -> new VirtualThreadTransport(processes);
                };
        Counting counting = new Counting(carrier);

        try (Server server = Server.using(built, counting)) {
            server.newSession("bench");
            tmux = server.version().toString();
            setUp.accept(server);
            // Warm: the first command of any mode pays for starting a server, which is not what
            // is being compared. Whatever the scenario needed is already in place, so none of it is
            // timed either.
            server.windows();
            int before = counting.dispatches.get();
            int spawnedBefore = processes.dispatches.get();
            long started = System.nanoTime();
            String output = work.apply(server);
            long millis = (System.nanoTime() - started) / 1_000_000;
            int dispatches = counting.dispatches.get() - before;
            int spawned = processes.dispatches.get() - spawnedBefore;
            server.killServer();
            return new Measured(mode.name(), millis, dispatches, spawned, mode == ExecutionMode.CONTROL, output);
        } finally {
            counting.close();
        }
    }

    // --------------------------------------------------------------------------------- the table

    private String render(
            List<Measured> traversal, List<Measured> creation, List<Measured> grouping, Map<String, String> sameQuery) {
        StringBuilder out = new StringBuilder();
        out.append("# Execution modes, measured\n\n")
                .append("Regenerated by `./gradlew modeBenchmark`. Never edit by hand.\n\n")
                .append("Measured against tmux `")
                .append(tmux)
                .append("`, ")
                .append(ROUNDS)
                .append(" rounds per scenario, on one machine at one moment. ")
                .append("Read the shape, not the milliseconds.\n\n");

        out.append("## Reading the hierarchy\n\n");
        table(out, "mode", traversal);
        out.append("\n## Building a workspace\n\n");
        table(out, "mode", creation);

        out.append("\n## Collapsing round trips\n\n")
                .append("The same ")
                .append(ROUNDS)
                .append(" windows, asked for three ways under each carrier. ")
                .append("`batch()` and `chain()` are not modes: they compose with whichever one is in force.\n\n");
        table(out, "carrier and strategy", grouping);
        out.append("\nA group is carried by a process even under `CONTROL`, because a control client ")
                .append("frames one reply per command and a group would desynchronise the stream. ")
                .append("The process column is counted, not assumed, so that shows up here.\n");

        out.append("\n## The same query, every way\n\n")
                .append("`server.windows().stream().filter(Window_.name().startsWith(\"bench\"))`, ")
                .append("and what each mode answered:\n\n")
                .append("| mode | result |\n| --- | --- |\n");
        sameQuery.forEach((mode, result) ->
                out.append("| `").append(mode).append("` | `").append(result).append("` |\n"));
        out.append("\nIdentical, which is the point: a mode changes the carrying and not the answer. ")
                .append("`ExecutionModeConformanceTest` asserts that; this shows it.\n");
        return out.toString();
    }

    private static void table(StringBuilder out, String heading, List<Measured> rows) {
        out.append("| %s | wall clock | commands dispatched | tmux processes |%n".formatted(heading))
                .append("| --- | --- | --- | --- |\n");
        for (Measured row : rows) {
            out.append("| `%s` | %d ms | %d | %s |%n"
                    .formatted(row.label(), row.millis(), row.dispatches(), processes(row)));
        }
    }

    /** What the processes cost, said the way the carrier spends it. */
    private static String processes(Measured row) {
        if (!row.viaClient()) {
            return Integer.toString(row.processes());
        }
        return row.processes() == 0 ? "1 client, reused" : "1 client, reused + " + row.processes();
    }
}
