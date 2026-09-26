package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What a server needs to know before it runs anything. */
final class ServerConfigTest {

    @Test
    void theDefaultsAreUsableWithoutSayingAnything() {
        ServerConfig config = ServerConfig.builder().build();

        assertEquals("tmux", config.binary());
        assertEquals(ServerEndpoint.defaultSocket(), config.endpoint());
        assertEquals(Optional.empty(), config.configFile());
        assertFalse(config.force256Colors());
        assertTrue(config.defaultTimeout().toSeconds() > 0, "a request must have a deadline it can reach");
        assertEquals(4, config.maxConcurrentCommands());
    }

    /** Opening a server builds its transport, and nothing else: no tmux runs here. */
    @Test
    void anOpenedServerAdmitsAsManyCommandsAsItsConfigSays() {
        try (Server server =
                Server.open(ServerConfig.builder().maxConcurrentCommands(2).build())) {
            assertEquals(2, server.admissionBound());
        }
    }

    @Test
    void concurrentCommandsAreAtLeastOne() {
        assertThrows(
                IllegalArgumentException.class, () -> ServerConfig.builder().maxConcurrentCommands(0));
        assertEquals(
                7,
                ServerConfig.builder().maxConcurrentCommands(7).build().toBuilder()
                        .build()
                        .maxConcurrentCommands());
    }

    @Test
    void everyChoiceIsCarried() {
        Path socket = Path.of("/tmp/libtmux/s");
        Path conf = Path.of("/tmp/libtmux/empty.conf");

        ServerConfig config = ServerConfig.builder()
                .binary("/usr/local/bin/tmux")
                .endpoint(ServerEndpoint.socketPath(socket))
                .configFile(conf)
                .defaultTimeout(Duration.ofSeconds(5))
                .build();

        assertEquals("/usr/local/bin/tmux", config.binary());
        assertEquals(ServerEndpoint.socketPath(socket), config.endpoint());
        assertEquals(Optional.of(conf), config.configFile());
        assertEquals(Duration.ofSeconds(5), config.defaultTimeout());
    }

    @Test
    void toBuilderCopiesEveryChoiceAndChangesOnlyWhatIsAsked() {
        ServerConfig original = ServerConfig.builder()
                .binary("/usr/local/bin/tmux")
                .endpoint(ServerEndpoint.namedSocket("fixture"))
                .configFile(Path.of("/tmp/empty.conf"))
                .defaultTimeout(Duration.ofSeconds(5))
                .build();

        ServerConfig derived =
                original.toBuilder().defaultTimeout(Duration.ofSeconds(9)).build();

        assertEquals(Duration.ofSeconds(9), derived.defaultTimeout());
        assertEquals(original.binary(), derived.binary());
        assertEquals(original.endpoint(), derived.endpoint());
        assertEquals(original.configFile(), derived.configFile());
        assertEquals(Duration.ofSeconds(5), original.defaultTimeout(), "the original is untouched");
    }

    @Test
    void reusingABuilderCannotReachBackIntoWhatItAlreadyBuilt() {
        ServerConfig.Builder builder = ServerConfig.builder().binary("tmux");
        ServerConfig built = builder.build();

        builder.binary("/somewhere/else/tmux");

        assertEquals("tmux", built.binary());
    }

    @Test
    void theCommandPrefixIsTheBinaryThenTheServerSelection() {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.namedSocket("fixture"))
                .configFile(Path.of("/tmp/empty.conf"))
                .build();

        assertEquals(
                "[tmux, -u, -L, fixture, -f, /tmp/empty.conf]",
                config.endpointCommand().toString());
    }

    @Test
    void aConfigFileIsOmittedRatherThanGuessedAt() {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.namedSocket("fixture"))
                .build();

        assertEquals("[tmux, -u, -L, fixture]", config.endpointCommand().toString());
    }

    /**
     * Not cosmetic and not conditional: tmux decides from the environment whether its client reads
     * UTF-8, and replaces every non-ASCII character in a reply to a client it judges otherwise. A
     * library cannot leave that to whatever locale the caller's JVM was started in.
     */
    @Test
    void everyCommandDeclaresThatThisClientReadsUtf8() {
        ServerConfig config = ServerConfig.builder().build();

        assertEquals(
                "-u",
                config.endpointCommand().get(1),
                "the flag rides with the binary, so no call site can send a command without it");
    }

    @Test
    void colorChoiceSurvivesCopiesAndCanReturnToDetection() {
        try (Server server = Server.builder().force256Colors(true).build()) {
            ServerConfig config = server.config();
            assertTrue(config.force256Colors());
            assertEquals(java.util.List.of("tmux", "-u", "-2"), config.endpointCommand());
            assertTrue(config.toBuilder().build().force256Colors());
            ServerConfig detected = config.toBuilder().force256Colors(false).build();
            assertFalse(detected.force256Colors());
            assertEquals(java.util.List.of("tmux", "-u"), detected.endpointCommand());
        }
    }

    @Test
    void invalidChoicesAreRejectedWhileTheyCanStillBeFixed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.builder().binary("").build(),
                "an empty binary would become an unexplained launch failure");
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.builder().defaultTimeout(Duration.ZERO).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.builder()
                        .defaultTimeout(Duration.ofSeconds(-1))
                        .build());
    }
}
