package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What a server needs to know before it runs anything. */
final class ServerConfigTest {

    @Test
    void theDefaultsAreUsableWithoutSayingAnything() {
        ServerConfig config = ServerConfig.builder().build();

        assertEquals("tmux", config.binary());
        assertEquals(ServerEndpoint.defaultSocket(), config.endpoint());
        assertEquals(Optional.empty(), config.configFile());
        assertTrue(config.defaultTimeout().toSeconds() > 0, "a request must have a deadline it can reach");
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
                "[tmux, -L, fixture, -f, /tmp/empty.conf]",
                config.endpointCommand().toString());
    }

    @Test
    void aConfigFileIsOmittedRatherThanGuessedAt() {
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.namedSocket("fixture"))
                .build();

        assertEquals("[tmux, -L, fixture]", config.endpointCommand().toString());
    }

    @Test
    void aCarrierNamedInCodeBeatsOneNamedOutsideIt() {
        ExecutionMode chosen = withProperty(
                "CONTROL",
                () -> ServerConfig.builder().mode(ExecutionMode.DIRECT).build().mode());

        assertEquals(ExecutionMode.DIRECT, chosen, "code that names a carrier has said more than an ambient setting");
    }

    @Test
    void aPropertyChoosesTheCarrierWhenNothingInCodeDoes() {
        ExecutionMode chosen =
                withProperty("CONTROL", () -> ServerConfig.builder().build().mode());

        assertEquals(ExecutionMode.CONTROL, chosen);
    }

    @Test
    void theCarrierIsDirectWhenNothingChoosesOne() {
        assumeTrue(System.getenv(ExecutionMode.VARIABLE) == null, "this shell has already chosen a carrier");

        ExecutionMode chosen =
                withProperty(null, () -> ServerConfig.builder().build().mode());

        assertEquals(ExecutionMode.DIRECT, chosen);
    }

    @Test
    void copyingAConfigKeepsTheCarrierItAlreadyResolved() {
        ServerConfig ambient =
                withProperty("CONTROL", () -> ServerConfig.builder().build());

        assertEquals(
                ExecutionMode.CONTROL,
                ambient.toBuilder().build().mode(),
                "a copy that re-read the property would differ from what it copied");
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

    /** Runs the body with the mode property set as asked, and puts back whatever was there before. */
    private static <T> T withProperty(@Nullable String value, Supplier<T> body) {
        String previous = System.getProperty(ExecutionMode.PROPERTY);
        if (value == null) {
            System.clearProperty(ExecutionMode.PROPERTY);
        } else {
            System.setProperty(ExecutionMode.PROPERTY, value);
        }
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(ExecutionMode.PROPERTY);
            } else {
                System.setProperty(ExecutionMode.PROPERTY, previous);
            }
        }
    }
}
