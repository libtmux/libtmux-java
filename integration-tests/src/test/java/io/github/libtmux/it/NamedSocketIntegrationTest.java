package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server addressed by name. The fixture's sweep matches {@code -S} and these carry {@code -L}, so
 * nothing but the {@code finally} below ends them.
 */
final class NamedSocketIntegrationTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    /**
     * Short because {@code TMUX_TMPDIR} already spends about eighty of the ~104 bytes a unix socket
     * path may hold. The pid keeps concurrent runs — Gradle's workers, the matrix's lanes — apart.
     */
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
                server.killServer();
            }
        }
    }

    @Test
    void twoNamesAreTwoServers(@TempDir Path directory) throws Exception {
        try (Server first = openNamed(NAMESPACE + "-b", directory);
                Server second = openNamed(NAMESPACE + "-c", directory)) {
            try {
                first.newSession("in-first");
                second.newSession("in-second");

                assertTrue(first.hasSession("in-first"));
                assertTrue(second.hasSession("in-second"));
                assertTrue(!first.hasSession("in-second"), "the first server can see the second's session");
                assertTrue(!second.hasSession("in-first"), "the second server can see the first's session");
                assertNotEquals(reportedSocket(first), reportedSocket(second), "both names resolved to one socket");
            } finally {
                first.killServer();
                second.killServer();
            }
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
