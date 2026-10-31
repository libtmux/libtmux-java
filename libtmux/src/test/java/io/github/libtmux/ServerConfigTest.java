package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** What a server needs to know before it runs anything. */
final class ServerConfigTest {

    @Test
    void theDefaultsAreUsableWithoutSayingAnything() {
        ServerConfig config = ServerConfig.builder().build(Map.of());

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
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.defaultSocket())
                .force256Colors(true)
                .build()) {
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

    // ------------------------------------------------------------------------------ environment

    @Test
    void aConfigGivenNoEndpointTakesTheServerTheEnvironmentNames() {
        assertEquals(
                ServerEndpoint.namedSocket("work"),
                ServerConfig.builder().build(Map.of("LIBTMUX_SOCKET", "work")).endpoint());
        assertEquals(
                ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-test/env/s")),
                ServerConfig.builder()
                        .build(Map.of("LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s"))
                        .endpoint());
        assertEquals(
                "[tmux, -u]",
                ServerConfig.builder().build(Map.of()).endpointCommand().toString());
    }

    @Test
    void anEndpointTheCallerGaveBeatsTheEnvironment() {
        ServerEndpoint given = ServerEndpoint.namedSocket("given");

        ServerConfig config = ServerConfig.builder()
                .endpoint(given)
                .build(Map.of("LIBTMUX_SOCKET", "work", "LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s"));

        assertEquals(given, config.endpoint());
        assertEquals("[tmux, -u, -L, given]", config.endpointCommand().toString());
    }

    /** A derived config that re-read the environment could land on a different server than its source. */
    @Test
    void aDerivedConfigKeepsTheServerItsSourceNamed() {
        ServerConfig source = ServerConfig.builder().build(Map.of("LIBTMUX_SOCKET", "first"));

        ServerConfig derived = source.toBuilder().build(Map.of("LIBTMUX_SOCKET", "second"));

        assertEquals(ServerEndpoint.namedSocket("first"), derived.endpoint());
    }

    @Test
    void anEnvironmentThatNamesNoValidSocketFailsAtBuildNotAtFirstCommand() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.builder().build(Map.of("LIBTMUX_SOCKET", "nested/name")));
    }

    /**
     * The process environment is what {@code build()} reads, which no injected map can show: a child
     * JVM is given the variables and prints the command its default config would run.
     */
    @Test
    void buildReadsTheProcessEnvironmentWhenNoEndpointWasGiven() throws Exception {
        assertEquals("[tmux, -u]", childConfig(Map.of()));
        assertEquals("[tmux, -u, -L, from-process]", childConfig(Map.of("LIBTMUX_SOCKET", "from-process")));
        assertEquals(
                "[tmux, -u, -S, /tmp/libtmux-java-test/env/s]",
                childConfig(Map.of(
                        "LIBTMUX_SOCKET", "from-process", "LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s")));
    }

    private static String childConfig(Map<String, String> variables) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                DefaultConfigMain.class.getName());
        builder.environment().remove("LIBTMUX_SOCKET");
        builder.environment().remove("LIBTMUX_SOCKET_PATH");
        builder.environment().putAll(variables);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process child = builder.start();
        try {
            String printed = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "the child JVM did not exit");
            assertEquals(0, child.exitValue());
            return printed.strip();
        } finally {
            child.destroyForcibly();
        }
    }

    /** Builds a config with nothing said and prints the command prefix it would run. */
    public static final class DefaultConfigMain {
        private DefaultConfigMain() {}

        public static void main(String[] args) {
            System.out.println(ServerConfig.builder().build().endpointCommand());
        }
    }
}
