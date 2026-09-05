package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server addressed by name. The fixture's sweep matches {@code -S} and these carry {@code -L}, so
 * nothing but the {@code finally} below ends them.
 */
final class NamedSocketIntegrationTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");
    private static final int UNIX_FILE_TYPE = 0170000;
    private static final int UNIX_SOCKET = 0140000;

    /** The pid keeps concurrent Gradle workers and matrix lanes apart inside their quarantine. */
    private static final String NAMESPACE = "ltj-" + ProcessHandle.current().pid();

    @Test
    void aNamedServerLandsInThisPortsOwnDirectory(@TempDir Path directory) throws Exception {
        String name = NAMESPACE + "-a";

        try (Server server = openNamed(name, directory)) {
            try {
                server.newSession("named");

                Path socket = Path.of(reportedSocket(server));

                assertEquals(name, socket.getFileName().toString(), "tmux resolved a different name");
                assertTrue(
                        socket.startsWith(tmuxTmpDir()),
                        "the socket escaped this port's namespace into " + socket.getParent());
                assertTrue(Files.exists(socket), "tmux reported a socket that is not there");
                assertTrue(
                        socket.toString().length() <= 104,
                        "the socket path is at the limit a unix socket can carry: " + socket);
            } finally {
                reclaimNamedServer(server, name);
            }
        }
    }

    @Test
    void twoNamesAreTwoServers(@TempDir Path directory) throws Exception {
        String firstName = NAMESPACE + "-b";
        String secondName = NAMESPACE + "-c";
        try (Server first = openNamed(firstName, directory);
                Server second = openNamed(secondName, directory)) {
            try {
                first.newSession("in-first");
                second.newSession("in-second");

                assertTrue(first.hasSession("in-first"));
                assertTrue(second.hasSession("in-second"));
                assertTrue(!first.hasSession("in-second"), "the first server can see the second's session");
                assertTrue(!second.hasSession("in-first"), "the second server can see the first's session");
                assertNotEquals(reportedSocket(first), reportedSocket(second), "both names resolved to one socket");
            } finally {
                try {
                    reclaimNamedServer(first, firstName);
                } finally {
                    reclaimNamedServer(second, secondName);
                }
            }
        }
    }

    private static void reclaimNamedServer(Server server, String name) throws IOException {
        ProcessHandle process;
        Path quarantine;
        Path socket;
        BasicFileAttributes owned;
        try {
            List<String> identity = server.cmd("display-message", "-p", "#{pid}\t#{socket_path}")
                    .stdout();
            if (identity.size() != 1) {
                throw new AssertionError("tmux reported identity rows " + identity);
            }

            String[] fields = identity.get(0).split("\t", -1);
            assertEquals(2, fields.length, "tmux reported a malformed identity row " + identity.get(0));
            process = ProcessHandle.of(Long.parseLong(fields[0]))
                    .orElseThrow(() -> new AssertionError("the reported tmux process is already gone"));

            Path configuredQuarantine = tmuxTmpDir().toAbsolutePath().normalize();
            quarantine = configuredQuarantine.toRealPath();
            assertEquals(
                    configuredQuarantine, quarantine, "refusing to reclaim a socket through a linked quarantine path");
            Path reported = Path.of(fields[1]).toAbsolutePath().normalize();
            assertTrue(!Files.isSymbolicLink(reported), "refusing to reclaim a socket through a symbolic link");
            socket = reported.toRealPath();
            assertEquals(reported, socket, "refusing to reclaim a socket through a linked directory");
            assertTrue(
                    !socket.equals(quarantine) && socket.startsWith(quarantine),
                    "refusing to reclaim a socket outside this port's quarantine");
            assertEquals(name, socket.getFileName().toString(), "tmux reported another server's socket");
            owned = Files.readAttributes(socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            assertTrue(isUnixSocket(socket), "tmux did not report a unix-domain socket inode");
            assertTrue(owned.fileKey() != null, "the filesystem cannot identify the socket inode");
        } finally {
            server.killServer();
        }

        assertTrue(Await.until(() -> !process.isAlive()), "the named tmux server did not exit");
        if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes stale =
                    Files.readAttributes(socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            assertTrue(isUnixSocket(socket), "refusing to remove a non-socket replacement");
            assertEquals(owned.fileKey(), stale.fileKey(), "refusing to remove a replacement socket inode");
            Files.delete(socket);
        }
        assertTrue(Files.notExists(socket, LinkOption.NOFOLLOW_LINKS), "the named tmux socket was not reclaimed");
        pruneEmptyParents(Objects.requireNonNull(socket.getParent()), quarantine);
    }

    private static boolean isUnixSocket(Path path) throws IOException {
        int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        return (mode & UNIX_FILE_TYPE) == UNIX_SOCKET;
    }

    private static void pruneEmptyParents(Path directory, Path quarantine) throws IOException {
        Path candidate = directory;
        while (candidate != null && !candidate.equals(quarantine)) {
            assertTrue(candidate.startsWith(quarantine), "refusing to prune outside the tmux quarantine");
            if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                candidate = candidate.getParent();
                continue;
            }
            assertTrue(
                    Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS),
                    "refusing to prune a non-directory from the tmux quarantine");
            try {
                Files.delete(candidate);
            } catch (DirectoryNotEmptyException e) {
                return;
            }
            candidate = candidate.getParent();
        }
    }

    private static Server openNamed(String name, Path directory) throws IOException {
        return Server.open(ServerConfig.builder()
                .binary(TMUX)
                .endpoint(ServerEndpoint.namedSocket(name))
                .configFile(emptyConfig(directory, name))
                .build());
    }

    private static String reportedSocket(Server server) {
        List<String> reported =
                server.cmd("display-message", "-p", "#{socket_path}").stdout();
        assertEquals(1, reported.size(), "tmux answered with something other than one socket: " + reported);
        return reported.get(0);
    }

    /** Empty so a developer's own tmux.conf cannot decide what these servers do. */
    private static Path emptyConfig(Path directory, String name) throws IOException {
        Path config = directory.resolve(name + ".conf");
        Files.writeString(config, "");
        return config;
    }

    private static Path tmuxTmpDir() {
        String configured = System.getenv("TMUX_TMPDIR");
        assertTrue(configured != null && !configured.isEmpty(), "the build did not quarantine TMUX_TMPDIR");
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
