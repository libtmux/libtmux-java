package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.junit5.TmuxExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code toBuilder} lends the original's transport onward only when the original borrowed it too, so
 * a server derived from an owned one must not be left holding the connection its parent closes.
 */
@ExtendWith(TmuxExtension.class)
final class ServerDerivationIntegrationTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    @Test
    void aDerivedServerAddressesTheSameTmux(Server server) {
        try (Server derived = server.toBuilder().build()) {
            derived.newSession("made-through-the-derived-one");

            assertEquals(server.identity(), derived.identity());
            assertTrue(
                    server.hasSession("made-through-the-derived-one"),
                    "the derived server reached a tmux the original cannot see");
        }
    }

    /**
     * Ended through a third handle: this fails with the derived server's transport closed, and a
     * cleanup issued through that transport would fail too and strand the server.
     */
    @Test
    void aDerivedServerOutlivesTheOriginalBeingClosed(@TempDir Path directory) throws IOException {
        ServerConfig hosting = hosting(directory);
        Server original = Server.open(hosting);
        Server derived = original.toBuilder().build();
        try {
            original.newSession("host");

            original.close();

            assertTrue(
                    derived.cmd("display-message", "-p", "ok").succeeded(),
                    "the derived server was left holding the connection its parent closed");
            assertTrue(derived.hasSession("host"));
        } finally {
            derived.close();
            original.close();
            try (Server ending = Server.open(hosting)) {
                ending.killServer();
            }
        }
    }

    @Test
    void derivingChangesOnlyWhatIsAsked(Server server) {
        Duration before = server.config().defaultTimeout();

        try (Server derived =
                server.toBuilder().defaultTimeout(Duration.ofSeconds(7)).build()) {
            assertEquals(Duration.ofSeconds(7), derived.config().defaultTimeout());
            assertEquals(server.config().endpoint(), derived.config().endpoint());
            assertEquals(server.config().binary(), derived.config().binary());
            assertEquals(before, server.config().defaultTimeout(), "the original is untouched");

            assertTrue(derived.cmd("display-message", "-p", "ok").succeeded());
        }
    }

    private static ServerConfig hosting(Path directory) throws IOException {
        Path config = directory.resolve("empty.conf");
        Files.writeString(config, "");
        return ServerConfig.builder()
                .binary(TMUX)
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .configFile(config)
                .build();
    }
}
