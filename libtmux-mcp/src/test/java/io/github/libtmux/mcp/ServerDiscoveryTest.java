package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class ServerDiscoveryTest {

    private static final String STRICT_PROBE = """
            #!/bin/sh
            [ "$#" -eq 5 ] || exit 90
            [ "$1" = '-S' ] || exit 91
            [ "$3" = 'list-sessions' ] || exit 92
            [ "$4" = '-F' ] || exit 93
            [ "$5" = '#{session_id}' ] || exit 94
            printf '%s\n' "$*" >> "${2}.calls"
            """;

    @Test
    void staleSocketIsUnreachableAfterOneStrictProbe(@TempDir Path root) throws Exception {
        Path socket = standard(root, "stale");
        staleSocket(socket);
        Path binary = binary(root, "exit 1\n");

        ServerDiscovery.Result result =
                discovery(root, 8, 32, 2, Duration.ofSeconds(1)).discover(binary.toString(), null);

        assertEquals(1, result.servers().size());
        ServerDiscovery.KnownServer known = result.servers().get(0);
        assertEquals(socket, known.socket());
        assertEquals(ServerDiscovery.State.UNREACHABLE, known.state());
        assertNull(known.sessions());
        assertEquals(1, Files.readAllLines(callLog(socket)).size(), "one candidate gets one strict probe");
    }

    @Test
    void candidateIsNotProbedWhenTmuxCannotStart(@TempDir Path root) throws Exception {
        Path socket = standard(root, "socket");
        try (SocketSet ignored = sockets(List.of(socket))) {
            Path missingBinary = root.resolve("missing-tmux");

            ServerDiscovery.Result result =
                    discovery(root, 8, 32, 1, Duration.ofSeconds(1)).discover(missingBinary.toString(), null);

            assertEquals(
                    ServerDiscovery.State.NOT_PROBED, result.servers().get(0).state());
            assertNull(result.servers().get(0).sessions());
        }
    }

    @Test
    void slowSocketHasATypedDeadline(@TempDir Path root) throws Exception {
        Path socket = standard(root, "slow");
        try (SocketSet ignored = sockets(List.of(socket))) {
            Path binary = binary(root, "exec sleep 10\n");
            ServerDiscovery discovery = discovery(root, 8, 32, 1, Duration.ofMillis(200));

            long started = System.nanoTime();
            ServerDiscovery.Result result = discovery.discover(binary.toString(), null);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(
                    ServerDiscovery.State.TIMED_OUT, result.servers().get(0).state());
            assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "per-candidate deadline was " + elapsed);
            assertEquals(1, Files.readAllLines(callLog(socket)).size());
        }
    }

    @Test
    void candidateCapReportsTruncation(@TempDir Path root) throws Exception {
        List<Path> candidates =
                List.of(standard(root, "a"), standard(root, "b"), standard(root, "c"), standard(root, "d"));
        try (SocketSet ignored = sockets(candidates)) {
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 2, 16, 2, Duration.ofSeconds(1)).discover(binary.toString(), null);

            assertEquals(2, result.servers().size());
            assertTrue(result.truncated());
            assertTrue(result.servers().stream().allMatch(server -> server.state() == ServerDiscovery.State.RUNNING));
            assertTrue(result.servers().stream()
                    .allMatch(server -> Integer.valueOf(1).equals(server.sessions())));
        }
    }

    @Test
    void candidateCapSelectsLexicallyFromTheInspectedEntries(@TempDir Path root) throws Exception {
        List<Path> candidates =
                List.of(standard(root, "d"), standard(root, "c"), standard(root, "b"), standard(root, "a"));
        try (SocketSet ignored = sockets(candidates)) {
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 2, 16, 2, Duration.ofSeconds(1)).discover(binary.toString(), null);

            assertEquals(
                    List.of(standard(root, "a"), standard(root, "b")),
                    result.servers().stream()
                            .map(ServerDiscovery.KnownServer::socket)
                            .toList());
            assertTrue(result.truncated());
        }
    }

    @Test
    void processConcurrencyNeverExceedsTheConfiguredBound(@TempDir Path root) throws Exception {
        List<Path> candidates =
                List.of(standard(root, "a"), standard(root, "b"), standard(root, "c"), standard(root, "d"));
        try (SocketSet ignored = sockets(candidates)) {
            Path binary = binary(root, """
                    : > "${2}.started"
                    while [ ! -e "${2}.release" ]; do
                        sleep 0.01
                    done
                    printf '%%1\n'
                    """);
            ServerDiscovery discovery = discovery(root, 4, 16, 2, Duration.ofSeconds(2));
            FutureTask<ServerDiscovery.Result> task =
                    new FutureTask<>(() -> discovery.discover(binary.toString(), null));
            Thread worker = Thread.startVirtualThread(task);

            await(() -> started(candidates) >= 2, Duration.ofSeconds(2));
            Thread.sleep(150);
            int firstWave = started(candidates);
            releaseStarted(candidates);
            assertEquals(2, firstWave, "a third process started before one of the first two left");
            await(() -> started(candidates) == 4, Duration.ofSeconds(2));
            releaseStarted(candidates);

            ServerDiscovery.Result result = task.get();
            worker.join();
            assertTrue(result.servers().stream().allMatch(server -> server.state() == ServerDiscovery.State.RUNNING));
        }
    }

    @Test
    void uidFailureDoesNotScanTheRootFallbackDirectory(@TempDir Path root) throws Exception {
        Path rootFallback = root.resolve("tmux-0/would-have-been-probed");
        try (SocketSet ignored = sockets(List.of(rootFallback))) {
            Path binary = binary(root, "printf '%%1\\n'\n");
            ServerDiscovery discovery = new ServerDiscovery(
                    root,
                    () -> {
                        throw new IOException("uid unavailable");
                    },
                    8,
                    32,
                    2,
                    Duration.ofSeconds(1));

            ServerDiscovery.Result result = discovery.discover(binary.toString(), null);

            assertTrue(result.servers().isEmpty());
            assertTrue(Objects.requireNonNull(result.scanNote()).contains("user"));
            assertFalse(Files.exists(callLog(rootFallback)), "UID failure must not become uid 0");
        }
    }

    @Test
    void currentCustomSocketOutsideTheStandardDirectoryIsIncludedOnce(@TempDir Path root) throws Exception {
        Path standard = standard(root, "standard");
        Path custom = root.resolve("custom/current");
        try (SocketSet ignored = sockets(List.of(standard, custom))) {
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 8, 32, 2, Duration.ofSeconds(1)).discover(binary.toString(), custom);

            assertEquals(2, result.servers().size());
            assertEquals(
                    1,
                    result.servers().stream()
                            .filter(server -> server.socket().equals(custom))
                            .count());
            assertEquals(1, Files.readAllLines(callLog(custom)).size());
        }
    }

    @Test
    void candidateCapReservesTheCurrentSocket(@TempDir Path root) throws Exception {
        Path first = standard(root, "a");
        Path second = standard(root, "b");
        Path current = root.resolve("z-custom/current");
        try (SocketSet ignored = sockets(List.of(first, second, current))) {
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 2, 16, 2, Duration.ofSeconds(1)).discover(binary.toString(), current);

            assertEquals(
                    List.of(first, current),
                    result.servers().stream()
                            .map(ServerDiscovery.KnownServer::socket)
                            .toList());
            assertTrue(result.truncated());
        }
    }

    @Test
    void currentSocketFoundByTheDirectoryScanIsNotProbedTwice(@TempDir Path root) throws Exception {
        Path current = standard(root, "current");
        try (SocketSet ignored = sockets(List.of(current))) {
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 8, 32, 2, Duration.ofSeconds(1)).discover(binary.toString(), current);

            assertEquals(1, result.servers().size());
            assertEquals(1, Files.readAllLines(callLog(current)).size());
        }
    }

    @Test
    void symlinkedSocketEntriesAreNotProbed(@TempDir Path root) throws Exception {
        Path outside = root.resolve("outside/socket");
        try (SocketSet ignored = sockets(List.of(outside))) {
            Path directory = standardDirectory(root);
            Files.createSymbolicLink(directory.resolve("alias"), outside);
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 8, 32, 2, Duration.ofSeconds(1)).discover(binary.toString(), null);

            assertTrue(result.servers().isEmpty());
            assertFalse(Files.exists(callLog(outside)));
        }
    }

    @Test
    void fifoEntriesAreNotProbedAsSockets(@TempDir Path root) throws Exception {
        Path directory = standardDirectory(root);
        Path fifo = directory.resolve("not-a-socket");
        Process process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertEquals(0, process.waitFor());
        Path binary = binary(root, "printf '%%1\\n'\n");

        ServerDiscovery.Result result =
                discovery(root, 8, 32, 2, Duration.ofSeconds(1)).discover(binary.toString(), null);

        assertTrue(result.servers().isEmpty());
        assertFalse(Files.exists(callLog(fifo)));
    }

    @Test
    void unsafeStandardSocketDirectoryIsNotScanned(@TempDir Path root) throws Exception {
        Path socket = standard(root, "untrusted");
        try (SocketSet ignored = sockets(List.of(socket))) {
            Files.setPosixFilePermissions(socket.getParent(), PosixFilePermissions.fromString("rwx---r-x"));
            Path binary = binary(root, "printf '%%1\\n'\n");

            ServerDiscovery.Result result =
                    discovery(root, 8, 32, 2, Duration.ofSeconds(1)).discover(binary.toString(), null);

            assertTrue(result.servers().isEmpty());
            assertTrue(Objects.requireNonNull(result.scanNote()).contains("unsafe"));
            assertFalse(Files.exists(callLog(socket)));
        }
    }

    private static ServerDiscovery discovery(
            Path root, int candidateLimit, int scanLimit, int maxConcurrency, Duration timeout) throws IOException {
        return new ServerDiscovery(root, () -> uid(root), candidateLimit, scanLimit, maxConcurrency, timeout);
    }

    private static Path standard(Path root, String name) throws IOException {
        return standardDirectory(root).resolve(name);
    }

    private static Path standardDirectory(Path root) throws IOException {
        Path directory = Files.createDirectories(root.resolve("tmux-" + uid(root)));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private static long uid(Path path) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:uid")).longValue();
    }

    private static Path binary(Path root, String behavior) throws IOException {
        Path binary = root.resolve("fake-tmux-" + System.nanoTime());
        Files.writeString(binary, STRICT_PROBE + behavior);
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        return binary;
    }

    private static SocketSet sockets(List<Path> paths) throws IOException {
        List<ServerSocketChannel> channels = new ArrayList<>();
        try {
            for (Path path : paths) {
                Files.createDirectories(path.getParent());
                ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                channel.bind(UnixDomainSocketAddress.of(path));
                channels.add(channel);
            }
            return new SocketSet(channels);
        } catch (IOException | RuntimeException e) {
            new SocketSet(channels).close();
            throw e;
        }
    }

    private static void staleSocket(Path path) throws IOException {
        try (SocketSet ignored = sockets(List.of(path))) {
            // Closing the listener deliberately leaves its socket node behind.
        }
    }

    private static Path callLog(Path socket) {
        return Path.of(socket + ".calls");
    }

    private static int started(List<Path> sockets) {
        return (int) sockets.stream()
                .filter(socket -> Files.exists(Path.of(socket + ".started")))
                .count();
    }

    private static void releaseStarted(List<Path> sockets) throws IOException {
        for (Path socket : sockets) {
            if (Files.exists(Path.of(socket + ".started"))) {
                Files.writeString(Path.of(socket + ".release"), "");
            }
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true within " + timeout);
    }

    private record SocketSet(List<ServerSocketChannel> channels) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (ServerSocketChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
