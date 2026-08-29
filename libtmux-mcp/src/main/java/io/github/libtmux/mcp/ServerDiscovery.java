package io.github.libtmux.mcp;

import com.sun.security.auth.module.UnixSystem;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTimeoutException;
import io.github.libtmux.transport.TmuxTransportException;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;

/** Finds tmux sockets without constructing a server. */
final class ServerDiscovery {

    private static final int DEFAULT_CANDIDATE_LIMIT = 32;
    private static final int DEFAULT_SCAN_LIMIT = 128;
    private static final int DEFAULT_CONCURRENCY = 4;
    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofMillis(1_500);
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_SOCKET_TYPE = 0140000;

    private final Path socketRoot;
    private final UidResolver uidResolver;
    private final int candidateLimit;
    private final int scanLimit;
    private final int maxConcurrency;
    private final Duration probeTimeout;

    ServerDiscovery(
            Path socketRoot,
            UidResolver uidResolver,
            int candidateLimit,
            int scanLimit,
            int maxConcurrency,
            Duration probeTimeout) {
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("candidateLimit is not positive");
        }
        if (scanLimit < candidateLimit) {
            throw new IllegalArgumentException("scanLimit is below candidateLimit");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency is not positive");
        }
        if (probeTimeout.isZero() || probeTimeout.isNegative()) {
            throw new IllegalArgumentException("probeTimeout is not positive");
        }
        this.socketRoot = socketRoot.toAbsolutePath().normalize();
        this.uidResolver = uidResolver;
        this.candidateLimit = candidateLimit;
        this.scanLimit = scanLimit;
        this.maxConcurrency = maxConcurrency;
        this.probeTimeout = probeTimeout;
    }

    static ServerDiscovery system() {
        String configured = System.getenv("TMUX_TMPDIR");
        Path root = Path.of(configured == null || configured.isEmpty() ? "/tmp" : configured);
        return new ServerDiscovery(
                root,
                ServerDiscovery::unixUid,
                DEFAULT_CANDIDATE_LIMIT,
                DEFAULT_SCAN_LIMIT,
                DEFAULT_CONCURRENCY,
                DEFAULT_PROBE_TIMEOUT);
    }

    Result discover(String binary, @Nullable Path currentSocket) {
        Scan scan = scan(currentSocket);
        if (scan.candidates().isEmpty()) {
            return new Result(List.of(), scan.truncated(), scan.note());
        }

        List<KnownServer> found = new ArrayList<>(scan.candidates().size());
        try (ProcessTransport transport = new ProcessTransport(maxConcurrency);
                ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<KnownServer>> futures = scan.candidates().stream()
                    .map(socket -> probes.submit(() -> probe(transport, binary, socket)))
                    .toList();
            boolean interrupted = false;
            for (int index = 0; index < futures.size(); index++) {
                Future<KnownServer> future = futures.get(index);
                if (interrupted) {
                    future.cancel(true);
                    found.add(notProbed(scan.candidates().get(index), "discovery was interrupted"));
                    continue;
                }
                try {
                    found.add(future.get());
                } catch (InterruptedException e) {
                    interrupted = true;
                    future.cancel(true);
                    found.add(notProbed(scan.candidates().get(index), "discovery was interrupted"));
                } catch (ExecutionException e) {
                    found.add(notProbed(scan.candidates().get(index), "tmux could not be probed"));
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return new Result(found, scan.truncated(), scan.note());
    }

    private Scan scan(@Nullable Path currentSocket) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        Path current =
                currentSocket == null ? null : currentSocket.toAbsolutePath().normalize();
        if (current != null) {
            candidates.add(current);
        }

        long uid;
        try {
            uid = uidResolver.currentUid();
            if (uid < 0) {
                throw new IOException("negative Unix UID");
            }
        } catch (IOException | RuntimeException e) {
            return scanResult(
                    candidates,
                    current,
                    false,
                    "The current Unix user could not be identified; standard sockets were not scanned.");
        }

        Path directory = socketRoot.resolve("tmux-" + uid);
        try {
            BasicFileAttributes directoryAttributes =
                    Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!directoryAttributes.isDirectory()) {
                return scanResult(
                        candidates,
                        current,
                        false,
                        "The standard socket path is not a directory, so it was not scanned.");
            }
            long owner = ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
            int mode = ((Number) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            if (owner != uid || (mode & 07) != 0) {
                return scanResult(
                        candidates,
                        current,
                        false,
                        "The standard socket directory has unsafe ownership or permissions; it was not scanned.");
            }
        } catch (NoSuchFileException e) {
            return scanResult(candidates, current, false, null);
        } catch (IOException | RuntimeException e) {
            return scanResult(candidates, current, false, "The standard socket directory could not be read.");
        }

        boolean truncated = false;
        int inspected = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                if (inspected == scanLimit) {
                    truncated = true;
                    break;
                }
                Path entry = iterator.next().toAbsolutePath().normalize();
                inspected++;
                if (!isSocket(entry) || candidates.contains(entry)) {
                    continue;
                }
                candidates.add(entry);
            }
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            return scanResult(
                    candidates, current, truncated, "The standard socket directory could not be read completely.");
        }
        return scanResult(candidates, current, truncated, null);
    }

    private Scan scanResult(Set<Path> candidates, @Nullable Path current, boolean truncated, @Nullable String note) {
        int scannedLimit = candidateLimit - (current == null ? 0 : 1);
        List<Path> bounded = new ArrayList<>(candidateLimit);
        candidates.stream()
                .filter(candidate -> !candidate.equals(current))
                .sorted(Comparator.comparing(Path::toString))
                .limit(scannedLimit)
                .forEach(bounded::add);
        if (current != null) {
            bounded.add(current);
            bounded.sort(Comparator.comparing(Path::toString));
        }
        return new Scan(bounded, truncated || candidates.size() > candidateLimit, note);
    }

    private static boolean isSocket(Path path) {
        try {
            int mode = (int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return (mode & UNIX_FILE_TYPE_MASK) == UNIX_SOCKET_TYPE;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private KnownServer probe(ProcessTransport transport, String binary, Path socket) {
        try {
            CommandResult result = transport.execute(new CommandRequest(
                    List.of(binary, "-S", socket.toString()),
                    List.of("list-sessions", "-F", "#{session_id}"),
                    probeTimeout));
            if (result.succeeded()) {
                return new KnownServer(socket, State.RUNNING, result.stdout().size(), null);
            }
            return new KnownServer(socket, State.UNREACHABLE, null, "tmux did not answer on this socket");
        } catch (TmuxTimeoutException e) {
            if (e.outcome() == DispatchOutcome.NOT_DISPATCHED) {
                return notProbed(socket, "tmux could not be started before the probe deadline");
            }
            return new KnownServer(socket, State.TIMED_OUT, null, "tmux did not answer before the probe deadline");
        } catch (TmuxTransportException e) {
            if (e.outcome() == DispatchOutcome.NOT_DISPATCHED) {
                return notProbed(socket, "tmux could not be started before the probe deadline");
            }
            return new KnownServer(socket, State.UNREACHABLE, null, "tmux could not be probed");
        } catch (RuntimeException e) {
            return notProbed(socket, "tmux could not be started");
        }
    }

    private static KnownServer notProbed(Path socket, String note) {
        return new KnownServer(socket, State.NOT_PROBED, null, note);
    }

    private static long unixUid() throws IOException {
        try {
            return new UnixSystem().getUid();
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("could not read Unix UID", e);
        }
    }

    enum State {
        RUNNING,
        UNREACHABLE,
        TIMED_OUT,
        NOT_PROBED
    }

    record KnownServer(
            Path socket,
            State state,
            @Nullable Integer sessions,
            @Nullable String note) {}

    record Result(
            List<KnownServer> servers,
            boolean truncated,
            @Nullable String scanNote) {
        Result {
            servers = List.copyOf(servers);
        }
    }

    @FunctionalInterface
    interface UidResolver {
        long currentUid() throws IOException;
    }

    private record Scan(
            List<Path> candidates,
            boolean truncated,
            @Nullable String note) {}
}
