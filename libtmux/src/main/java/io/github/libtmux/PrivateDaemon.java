package io.github.libtmux;

import io.github.libtmux.exception.ServerUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The tmux daemon {@link Server#start} owns, and the directory it lives in.
 *
 * <p>The daemon runs in the foreground as a child of this JVM ({@code -D}), so its exit is observed
 * on the process rather than inferred from a socket. Its environment has none of the caller's
 * selectors and a {@code TMUX_TMPDIR} inside the directory, so a tmux started from a pane without a
 * socket lands under the directory too, where {@link #close} looks for it.
 */
final class PrivateDaemon {

    private static final int UNIX_FILE_TYPE = 0170000;
    private static final int UNIX_SOCKET = 0140000;
    private static final Duration PROBE = Duration.ofSeconds(5);

    /**
     * The directory names the JVM that made it, the only durable record of who owns the server
     * inside: a registry cannot serve, because the run that most needs reaping was killed before it
     * could write anything. The pid alone is not a stable owner, so the JVM's start rides along.
     */
    private static final String PREFIX = prefix();

    /** pid and start: {@code k} and ticks since boot on Linux, epoch millis elsewhere. */
    private static final Pattern OWNER_WITH_START = Pattern.compile("libtmux-(\\d+)-(k?\\d+)-.*");

    /** pid alone, written by a version of the JUnit extension that predates {@link #OWNER_WITH_START}. */
    private static final Pattern OWNER = Pattern.compile("libtmux-(\\d+)-.*");

    /** How far two JVMs' readings of one process's start instant may differ (a stepped wall clock). */
    private static final long START_SLACK_MILLIS = Duration.ofMinutes(1).toMillis();

    /** How long a signalled abandoned server is given to go. */
    private static final Duration REAP = Duration.ofSeconds(30);

    private static final Set<Path> SWEPT = ConcurrentHashMap.newKeySet();
    private static final String[] NO_ARGUMENTS = {};

    private final ServerConfig config;
    private final Process process;
    private final Path directory;
    private final Duration timeout;

    private PrivateDaemon(ServerConfig config, Process process, Path directory, Duration timeout) {
        this.config = config;
        this.process = process;
        this.directory = directory;
        this.timeout = timeout;
    }

    /** The configuration of a client for this daemon. */
    ServerConfig config() {
        return config;
    }

    /**
     * Starts a daemon and waits until it answers.
     *
     * @throws ServerUnavailableException if tmux cannot be started or exits or stays silent
     */
    static PrivateDaemon start(ServerLaunch launch) {
        Path root = launch.root().toAbsolutePath().normalize();
        Path directory;
        try {
            Files.createDirectories(root);
            if (SWEPT.add(root)) {
                reapAbandoned(root);
            }
            directory = Files.createTempDirectory(root, PREFIX);
        } catch (IOException e) {
            throw new ServerUnavailableException("could not make a directory for the tmux server under " + root);
        }
        try {
            Path tmpdir = Files.createDirectory(directory.resolve("tmp"));
            Path socket = directory.resolve("s");
            ServerConfig config = ServerConfig.builder()
                    .binary(launch.binary())
                    .endpoint(ServerEndpoint.socketPath(socket))
                    .configFile(launch.configFile().orElse(Path.of("/dev/null")))
                    .build();
            ProcessBuilder builder = new ProcessBuilder(daemonCommand(config))
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(environment(System.getenv(), tmpdir));
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new ServerUnavailableException("could not start " + launch.binary() + ": " + e.getMessage());
            }
            PrivateDaemon daemon = new PrivateDaemon(config, process, directory, launch.timeout());
            try {
                daemon.awaitReady();
            } catch (RuntimeException failure) {
                try {
                    daemon.close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            return daemon;
        } catch (IOException | RuntimeException failure) {
            deleteTree(directory);
            if (failure instanceof RuntimeException unchecked) throw unchecked;
            throw new ServerUnavailableException(
                    "could not prepare the tmux server directory: " + failure.getMessage());
        }
    }

    private static List<String> daemonCommand(ServerConfig config) {
        List<String> command = new ArrayList<>(config.endpointCommand());
        // -D keeps the daemon in the foreground and turns exit-empty off, so it outlives its sessions.
        command.add(2, "-D");
        return command;
    }

    /** The environment the daemon gets: the caller's, without anything that selects a server. */
    static Map<String, String> environment(Map<String, String> caller, Path tmpdir) {
        Map<String, String> environment = new LinkedHashMap<>(caller);
        environment
                .keySet()
                .removeIf(name -> name.equals("TMUX")
                        || name.equals("TMUX_PANE")
                        || name.startsWith("LIBTMUX_SOCKET")
                        || name.equals("TMUX_TMPDIR"));
        environment.put("TMUX_TMPDIR", tmpdir.toString());
        return environment;
    }

    private void awaitReady() {
        Path socket = socket();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Server probe = Server.open(config)) {
            while (true) {
                if (!process.isAlive()) {
                    throw new ServerUnavailableException(
                            "tmux exited with status " + process.exitValue() + " before it answered");
                }
                if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS) && probe.isAlive(PROBE)) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    throw new ServerUnavailableException("tmux did not answer within " + timeout);
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerUnavailableException("interrupted while waiting for tmux to answer");
        }
    }

    private Path socket() {
        return directory.resolve("s");
    }

    /**
     * Ends the daemon, then any server left under the directory, and removes the directory.
     *
     * @throws IllegalStateException if a server still answers, in which case the directory stays, or
     *     if a program left a server running, which is stopped first
     */
    void close() {
        List<String> stuck = new ArrayList<>();
        List<String> left = new ArrayList<>();
        try (Server probe = Server.open(config)) {
            if (!stop(probe, process)) {
                stuck.add("s");
            }
        }
        for (Path candidate : socketsUnder(directory)) {
            if (candidate.equals(socket())) continue;
            try (Server other = Server.open(config.toBuilder()
                    .endpoint(ServerEndpoint.socketPath(candidate))
                    .build())) {
                if (!other.isAlive(PROBE)) continue;
                String name = directory.relativize(candidate).toString();
                left.add(name);
                try {
                    other.killServer(timeout);
                } catch (RuntimeException failed) {
                    // Whether it went is the next probe's to say.
                }
                if (other.isAlive(PROBE)) stuck.add(name);
            }
        }
        if (!stuck.isEmpty()) {
            throw new IllegalStateException(
                    "tmux still answers on " + String.join(", ", stuck) + " after kill-server; kept " + directory);
        }
        deleteTree(directory);
        if (!left.isEmpty()) {
            throw new IllegalStateException("the program left a tmux server running: " + String.join(", ", left));
        }
    }

    /** Asks politely, then insists, and answers whether the daemon is gone and no longer answers. */
    private boolean stop(Server probe, Process daemon) {
        try {
            probe.killServer(timeout);
        } catch (RuntimeException e) {
            // A server that already exited is the outcome wanted.
        }
        try {
            if (!daemon.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                daemon.destroyForcibly().waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !daemon.isAlive() && !probe.isAlive(PROBE);
    }

    /** Every socket under {@code directory}, without following links. */
    private static List<Path> socketsUnder(Path directory) {
        try (Stream<Path> walked = Files.walk(directory)) {
            return walked.filter(path -> {
                        try {
                            int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                            return (mode & UNIX_FILE_TYPE) == UNIX_SOCKET;
                        } catch (IOException | RuntimeException e) {
                            return false;
                        }
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort; the directory is the operating system's to reclaim.
                }
            });
        } catch (IOException e) {
            // As above.
        }
    }

    // ---------------------------------------------------------------------------- reaping

    private static String prefix() {
        ProcessHandle current = ProcessHandle.current();
        return "libtmux-" + current.pid()
                + startOf(current.pid()).map(start -> "-" + start).orElse("") + "-";
    }

    /**
     * Ends every tmux server under {@code root} whose owning JVM is gone, and answers how many
     * ended. Runs while other runs use the same root, so it may only touch abandoned servers.
     */
    static int reapAbandoned(Path root) {
        Path resolved = root.toAbsolutePath().normalize();
        List<Abandoned> abandoned = ProcessHandle.allProcesses()
                .map(handle -> abandonedServer(handle, resolved))
                .flatMap(Optional::stream)
                .toList();
        // Asked together and waited for afterwards, so one slow server does not serialise the rest.
        abandoned.stream().map(Abandoned::process).forEach(ProcessHandle::destroy);
        int reaped = 0;
        for (Abandoned server : abandoned) {
            if (ended(server.process())) {
                deleteTree(server.directory());
                reaped++;
            }
        }
        return reaped;
    }

    /** SIGTERM only asks: tmux ends every session and reaps each pane's children before it goes. */
    private static boolean ended(ProcessHandle handle) {
        try {
            handle.onExit().get(REAP.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return !handle.isAlive();
        }
    }

    /** Matched on the executable too: a shell whose command line mentions the socket is not a server. */
    private static Optional<Abandoned> abandonedServer(ProcessHandle handle, Path root) {
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
                        .map(directory -> new Abandoned(handle, directory));
            }
        }
        return Optional.empty();
    }

    /** A reused pid, or one whose live process started at a different instant, cannot spare this directory. */
    private static Optional<Path> abandonedDirectory(Path socket, Path root) {
        Path directory = socket.getParent();
        if (directory == null || !socket.startsWith(root)) {
            return Optional.empty();
        }
        Matcher withStart = OWNER_WITH_START.matcher(directory.getFileName().toString());
        if (!withStart.matches()) {
            Matcher named = OWNER.matcher(directory.getFileName().toString());
            if (!named.matches()) {
                // Something else's socket. Not this sweep's to judge.
                return Optional.empty();
            }
            return ProcessHandle.of(Long.parseLong(named.group(1))).isEmpty()
                    ? Optional.of(directory)
                    : Optional.empty();
        }
        long pid = Long.parseLong(withStart.group(1));
        return ownerChanged(pid, withStart.group(2)) ? Optional.of(directory) : Optional.empty();
    }

    /**
     * True when the pid is free, or a live process holds it but did not start when this directory
     * recorded. A live process whose start cannot be read is trusted rather than reaped.
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

    /** When a process started, read the same way by every JVM on the machine. */
    static Optional<String> startOf(long pid) {
        Optional<Long> ticks = linuxStartTicks(pid);
        if (ticks.isPresent()) {
            return Optional.of("k" + ticks.get());
        }
        return ProcessHandle.of(pid)
                .flatMap(process -> process.info().startInstant())
                .map(instant -> Long.toString(instant.toEpochMilli()));
    }

    /** Field 22 of {@code /proc/<pid>/stat}, counted after the command name's closing parenthesis. */
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

    private record Abandoned(ProcessHandle process, Path directory) {}
}
