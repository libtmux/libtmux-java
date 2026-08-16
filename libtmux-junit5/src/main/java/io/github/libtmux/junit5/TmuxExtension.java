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

    /**
     * The directory a fixture is made in names the JVM that made it, which is the only durable record
     * of who owns the server inside. A registry cannot serve: the run that most needs reaping is the
     * one that was killed before it could write anything down.
     */
    private static final String PREFIX = "libtmux-" + ProcessHandle.current().pid() + "-";

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

    /**
     * Ends every tmux server under {@code root} whose owning JVM is gone, and answers how many.
     *
     * <p>Ownership is read from the process, not from anything this library wrote down. A server is
     * reaped only when its executable is tmux, its {@code -S} argument is a socket under this root,
     * and the pid named by that socket's directory is no longer running.
     *
     * <p>All three conditions matter. A shell whose command line merely mentions the path is not a
     * server — the spike that produced this rule matched its own shell. And a live owner is left
     * alone, so a run may sweep while other runs are using the same root, which Gradle's per-module
     * workers and the tmux matrix's lanes both do.
     *
     * <p>A reused pid can only make this skip a server that was in fact abandoned, never make it end
     * one that was not. Leaving an orphan for the next run is the safe direction to be wrong in.
     *
     * <p>Counts what exited, not what was signalled. SIGTERM only asks: tmux answers it by destroying
     * every session, which kills each pane's children and waits to reap them, so the process outlives
     * the signal by as long as that takes.
     */
    static int reapAbandoned(Path root) {
        Path resolved = root.toAbsolutePath().normalize();
        List<ProcessHandle> abandoned = ProcessHandle.allProcesses()
                .filter(handle -> abandonedServer(handle, resolved))
                .toList();

        // Asked together, waited for afterwards, so one slow server does not serialise the rest.
        abandoned.forEach(ProcessHandle::destroy);

        int reaped = 0;
        for (ProcessHandle handle : abandoned) {
            if (ended(handle)) {
                reaped++;
            }
        }
        return reaped;
    }

    /** Whether a server signalled by {@link #reapAbandoned} is actually gone. */
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

    private static boolean abandonedServer(ProcessHandle handle, Path root) {
        ProcessHandle.Info info = handle.info();
        if (!info.command()
                .map(command -> Path.of(command).getFileName())
                .map(Path::toString)
                .filter("tmux"::equals)
                .isPresent()) {
            return false;
        }
        String[] argv = info.arguments().orElse(NO_ARGUMENTS);
        for (int index = 0; index + 1 < argv.length; index++) {
            if ("-S".equals(argv[index])) {
                return ownerIsGone(Path.of(argv[index + 1]).toAbsolutePath().normalize(), root);
            }
        }
        return false;
    }

    private static final String[] NO_ARGUMENTS = {};

    private static boolean ownerIsGone(Path socket, Path root) {
        Path directory = socket.getParent();
        if (directory == null || !socket.startsWith(root)) {
            return false;
        }
        Matcher named = OWNER.matcher(directory.getFileName().toString());
        if (!named.matches()) {
            // Something else's socket, or one from before this scheme. Not this sweep's to judge.
            return false;
        }
        return ProcessHandle.of(Long.parseLong(named.group(1))).isEmpty();
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
                if (SWEPT.compareAndSet(false, true)) {
                    reapAbandoned(Path.of(System.getProperty("java.io.tmpdir")));
                }
                Path root = Files.createTempDirectory(PREFIX);
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
                    current.cmd(List.of("kill-server"), PROBE);
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
        private static boolean awaitExit(Server server) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_MILLIS);
            while (System.nanoTime() < deadline) {
                try {
                    if (!server.cmd(List.of("list-sessions"), PROBE).succeeded()) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    return true;
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

        private static void deleteTree(Path root) {
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
    }
}
