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
 * Deriving one server from another.
 *
 * <p>{@code toBuilder} lends the original's transport onward only when the original borrowed it too.
 * {@code ServerTest} settles the borrowed half against a transport that can be asked what happened
 * to it; the owned half is what every caller reaches through {@link Server#open}, and what it turns
 * on — that the derived server is not left holding a connection its parent closes — needs a real
 * one at both ends.
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
     * Built here rather than taken from the fixture because the original has to be closed while the
     * test is still running, which is the fixture's job to do and not a test's.
     *
     * <p>Ended through a handle opened for the purpose. The way this fails is with the derived
     * server's transport closed, so a cleanup issued through that same transport would fail too and
     * leave a tmux running under a directory the fixture's sweep does not recognise.
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
