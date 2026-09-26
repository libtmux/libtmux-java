package io.github.libtmux.junit5;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.transport.CommandResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Gives each test its own tmux server and guarantees it is gone afterwards.
 *
 * <p>All state lives in the extension store, never in fields. JUnit reuses one declaratively
 * registered extension instance for every test in a class, so a field is shared state the moment
 * tests run in parallel, and one test's teardown then reaches another test's server.
 *
 * <p>The server is started eagerly, before the test body, and the aggregate that owns it is
 * registered before any process exists. Registering afterwards leaves a window in which a failure
 * partway through setup leaks a running tmux that nothing is left holding.
 *
 * <p>Teardown is driven from a lifecycle callback and the aggregate is also {@link AutoCloseable}
 * for the framework's own store handling. Relying on store auto-close alone is a silent dependency
 * on a configuration property: with it disabled that design releases nothing and reports nothing.
 * The aggregate closes exactly once, so either path alone is sufficient.
 */
public final class TmuxExtension implements ParameterResolver, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TmuxExtension.class);
    private static final String KEY = "fixture";

    private static final Path FIXTURE_ROOT = Path.of("/tmp/libtmux-java-test");

    /**
     * The directory a fixture is made in names the JVM that made it, which is the only durable record
     * of who owns the server inside. A registry cannot serve: the run that most needs reaping is the
     * one that was killed before it could write anything down.
     *
     * <p>The pid alone is not a stable owner: the OS recycles pids, and a fixture directory left by a
     * killed JVM can end up named for a pid a later, unrelated process now holds. The owning JVM's
     * start rules that out, so it rides along whenever the platform reports one.
     */
    private static final String PREFIX = prefix();

    private static String prefix() {
        ProcessHandle current = ProcessHandle.current();
        return "libtmux-" + current.pid()
                + startOf(current.pid()).map(start -> "-" + start).orElse("") + "-";
    }

    /** pid and start, the current naming: {@code k} and ticks since boot on Linux, epoch millis elsewhere. */
    private static final Pattern OWNER_WITH_START = Pattern.compile("libtmux-(\\d+)-(k?\\d+)-.*");

    /**
     * How far two JVMs' readings of one process's start instant may differ. Linux reports a start as
     * ticks since boot; the JDK adds the boot time it read once when it started, and that boot time
     * moves whenever the wall clock is stepped, by seconds under WSL2 in a few minutes. Only a
     * directory written before starts were recorded as ticks is compared this way.
     */
    private static final long START_SLACK_MILLIS = Duration.ofMinutes(1).toMillis();

    /** pid alone, written by a version of this extension that predates {@link #OWNER_WITH_START}. */
    private static final Pattern OWNER = Pattern.compile("libtmux-(\\d+)-.*");

    /** Every fixture this JVM currently holds a server for, so the shutdown hook knows what to end. */
    private static final Set<Fixture> LIVE = ConcurrentHashMap.newKeySet();

    /**
     * How long a signalled server is given to go. Generous because it is spent only on servers that
     * are already abandoned, and only once per JVM.
     */
    private static final Duration SHUTDOWN = Duration.ofSeconds(30);

    private static final AtomicBoolean SWEPT = new AtomicBoolean();

    static {
        // Covers the exits a lifecycle callback does not: a cancelled build, a SIGTERM, a
        // System.exit from something else in the JVM. Measured in docs/spikes/22: the hook runs on
        // termination and normal exit, and does not run on SIGKILL — which is what the sweep is for.
        Runtime.getRuntime().addShutdownHook(new Thread(TmuxExtension::releaseAll, "libtmux-fixture-shutdown"));
    }

    private static void releaseAll() {
        for (Fixture fixture : LIVE) {
            try {
                fixture.close();
            } catch (RuntimeException e) {
                // Shutdown is not a place to report; the sweep is the backstop for whatever survives.
            }
        }
    }

    static Path fixtureRoot() {
        return FIXTURE_ROOT;
    }

    /**
     * Ends every tmux server under {@code root} whose owning JVM is gone, and answers how many
     * ended. Runs while other runs are using the same root, so it may only touch abandoned servers.
     */
    static int reapAbandoned(Path root) {
        Path resolved = root.toAbsolutePath().normalize();
        List<AbandonedServer> abandoned = ProcessHandle.allProcesses()
                .map(handle -> abandonedServer(handle, resolved))
                .flatMap(Optional::stream)
                .toList();

        // Asked together, waited for afterwards, so one slow server does not serialise the rest.
        abandoned.stream().map(AbandonedServer::process).forEach(ProcessHandle::destroy);

        int reaped = 0;
        for (AbandonedServer server : abandoned) {
            if (ended(server.process())) {
                deleteTree(server.directory());
                reaped++;
            }
        }
        return reaped;
    }

    /**
     * SIGTERM only asks: tmux destroys every session and reaps each pane's children before it goes,
     * so a signalled server is still alive for as long as that takes.
     */
    private static boolean ended(ProcessHandle handle) {
        try {
            handle.onExit().get(SHUTDOWN.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return !handle.isAlive();
        }
    }

    /** Matched on the executable too: a shell whose command line mentions the socket is not a server. */
    private static Optional<AbandonedServer> abandonedServer(ProcessHandle handle, Path root) {
        ProcessHandle.Info info = handle.info();
        if (!info.command()
                .map(command -> Path.of(command).getFileName())
                .map(Path::toString)
                .filter("tmux"::equals)
                .isPresent()) {
            return Optional.empty();
        }
        String[] argv = info.arguments().orElse(NO_ARGUMENTS);
        for (int index = 0; index + 1 < argv.length; index++) {
            if ("-S".equals(argv[index])) {
                return abandonedDirectory(
                                Path.of(argv[index + 1]).toAbsolutePath().normalize(), root)
                        .map(directory -> new AbandonedServer(handle, directory));
            }
        }
        return Optional.empty();
    }

    private static final String[] NO_ARGUMENTS = {};

    /** A reused pid, or one whose live process started at a different instant, cannot spare this directory. */
    private static Optional<Path> abandonedDirectory(Path socket, Path root) {
        Path directory = socket.getParent();
        if (directory == null || !socket.startsWith(root)) {
            return Optional.empty();
        }
        String name = directory.getFileName().toString();
        Matcher withStart = OWNER_WITH_START.matcher(name);
        if (withStart.matches()) {
            long pid = Long.parseLong(withStart.group(1));
            return ownerChanged(pid, withStart.group(2)) ? Optional.of(directory) : Optional.empty();
        }
        Matcher named = OWNER.matcher(name);
        if (!named.matches()) {
            // Something else's socket, or one from before this scheme. Not this sweep's to judge.
            return Optional.empty();
        }
        return ProcessHandle.of(Long.parseLong(named.group(1))).isEmpty() ? Optional.of(directory) : Optional.empty();
    }

    /**
     * True when the pid is free, or a live process holds it but did not start when this directory
     * recorded. A live process whose start cannot be read is trusted rather than reaped: this
     * platform cannot tell a reused pid from the one that made the directory.
     */
    private static boolean ownerChanged(long pid, String recordedStart) {
        Optional<ProcessHandle> live = ProcessHandle.of(pid);
        if (live.isEmpty()) {
            return true;
        }
        if (recordedStart.startsWith("k")) {
            return linuxStartTicks(pid)
                    .map(ticks -> !recordedStart.equals("k" + ticks))
                    .orElse(false);
        }
        long recordedMillis = Long.parseLong(recordedStart);
        return live.get()
                .info()
                .startInstant()
                .map(instant -> Math.abs(instant.toEpochMilli() - recordedMillis) > START_SLACK_MILLIS)
                .orElse(false);
    }

    /**
     * When a process started, in a form every JVM on this machine reads the same way: the kernel's
     * own ticks since boot on Linux, and the platform's start instant elsewhere.
     */
    static Optional<String> startOf(long pid) {
        Optional<Long> ticks = linuxStartTicks(pid);
        if (ticks.isPresent()) {
            return Optional.of("k" + ticks.get());
        }
        return ProcessHandle.of(pid)
                .flatMap(process -> process.info().startInstant())
                .map(instant -> Long.toString(instant.toEpochMilli()));
    }

    /**
     * Field 22 of {@code /proc/<pid>/stat}. Counted after the command name's closing parenthesis,
     * since the name itself may hold spaces and parentheses.
     */
    private static Optional<Long> linuxStartTicks(long pid) {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        try {
            String text = Files.readString(stat);
            String[] fields = text.substring(text.lastIndexOf(')') + 2).split(" ", -1);
            return Optional.of(Long.parseLong(fields[19]));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private record AbandonedServer(ProcessHandle process, Path directory) {}

    static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort; a fixture directory is the operating system's to reclaim.
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove a tmux fixture directory", e);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        fixture(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        // Never claims a bare Path: another extension is entitled to resolve those.
        return type == Server.class || type == TmuxSocketPath.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Fixture fixture = fixture(context);
        return parameter.getParameter().getType() == Server.class
                ? fixture.server()
                : new TmuxSocketPath(fixture.socket());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Fixture fixture = context.getStore(NAMESPACE).get(KEY, Fixture.class);
        if (fixture != null) {
            fixture.close();
        }
    }

    private static Fixture fixture(ExtensionContext context) {
        // The aggregate is stored while it still owns nothing, so a failure during startup is still
        // reachable by teardown.
        Fixture fixture = context.getStore(NAMESPACE).getOrComputeIfAbsent(KEY, key -> new Fixture(), Fixture.class);
        fixture.start();
        return fixture;
    }

    /** One test's server, socket and directory, released exactly once. */
    static final class Fixture implements AutoCloseable {

        private static final Duration PROBE = Duration.ofSeconds(5);
        private static final long EXIT_MILLIS = 10_000;

        private @Nullable Path directory;
        private @Nullable Path socket;
        private @Nullable Server server;
        private boolean started;
        private boolean closed;

        synchronized void start() {
            if (started) {
                return;
            }
            started = true;
            try {
                // Once per JVM, before this run makes its first server: whatever a killed run left
                // behind is holding a pty and answering to a name this one might choose.
                Path fixtureRoot = fixtureRoot();
                Files.createDirectories(fixtureRoot);
                if (SWEPT.compareAndSet(false, true)) {
                    reapAbandoned(fixtureRoot);
                }
                Path root = Files.createTempDirectory(fixtureRoot, PREFIX);
                directory = root;
                Path config = root.resolve("tmux.conf");
                Files.writeString(config, "");
                Path endpoint = root.resolve("s");
                socket = endpoint;
                server = Server.open(ServerConfig.builder()
                        // Lets one suite run against a matrix of tmux builds rather than whichever
                        // one happens to be on PATH.
                        .binary(System.getProperty("libtmux.tmux", "tmux"))
                        .endpoint(ServerEndpoint.socketPath(endpoint))
                        .configFile(config)
                        .build());
                LIVE.add(this);
            } catch (IOException e) {
                throw new UncheckedIOException("could not create a tmux fixture directory", e);
            }
            CommandResult created = server().cmd("new-session", "-d", "-s", "libtmux");
            if (!created.succeeded()) {
                throw new IllegalStateException("could not start the fixture tmux: " + created.stderr());
            }
            verifyPromisedSocket();
        }

        /** tmux has to agree about which socket it is listening on, and it has to physically exist. */
        private void verifyPromisedSocket() {
            Path promised = socket();
            List<String> reported =
                    server().cmd("display-message", "-p", "#{socket_path}").stdout();
            if (!List.of(promised.toString()).equals(reported)) {
                throw new IllegalStateException("tmux reports socket " + reported + ", not the promised one");
            }
            if (!Files.exists(promised)) {
                throw new IllegalStateException("tmux reported a socket that does not exist");
            }
        }

        Server server() {
            Server current = server;
            if (current == null) {
                throw new IllegalStateException("the fixture server was never started");
            }
            return current;
        }

        Path socket() {
            Path current = socket;
            if (current == null) {
                throw new IllegalStateException("the fixture socket was never chosen");
            }
            return current;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            LIVE.remove(this);
            Server current = server;
            boolean exited = true;
            if (current != null) {
                try {
                    current.killServer(PROBE);
                } catch (RuntimeException e) {
                    // A server that already exited is the outcome teardown wanted.
                }
                exited = awaitExit(current);
                current.close();
            }
            if (!exited) {
                // Preserve the failure rather than unlinking a socket a live daemon still owns.
                throw new IllegalStateException("the fixture tmux did not exit; leaving " + directory + " in place");
            }
            Path root = directory;
            if (root != null) {
                deleteTree(root);
            }
        }

        /** Exit is proved by asking, not assumed from a kill that may have raced. */
        static boolean awaitExit(Server server) {
            return awaitExit(server, EXIT_MILLIS);
        }

        static boolean awaitExit(Server server, long timeoutMillis) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                try {
                    // isAlive rather than list-sessions: the latter also fails on a live server
                    // that has no sessions left, which teardown must not read as an exit.
                    if (!server.isAlive(PROBE)) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    return false;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }
}
