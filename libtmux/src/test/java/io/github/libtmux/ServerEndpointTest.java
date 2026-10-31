package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which tmux server to talk to, decided before any process runs. */
final class ServerEndpointTest {

    @Test
    void theDefaultEndpointAddsNoFlagsAndLetsTmuxChoose() {
        assertEquals(List.of(), ServerEndpoint.defaultSocket().flags());
    }

    @Test
    void aNamedSocketIsSelectedByName() {
        assertEquals(
                List.of("-L", "fixture"), ServerEndpoint.namedSocket("fixture").flags());
    }

    @Test
    void aSocketPathIsSelectedByPath() {
        assertEquals(
                List.of("-S", "/run/user/1000/libtmux/s"),
                ServerEndpoint.socketPath(Path.of("/run/user/1000/libtmux/s")).flags());
    }

    /**
     * Equality is lexical. Resolving would touch the filesystem, and the whole point of an endpoint
     * is that it can be compared, logged and stored before any socket exists.
     */
    @Test
    void twoSpellingsOfOnePathAreOneEndpoint() {
        ServerEndpoint direct = ServerEndpoint.socketPath(Path.of("/run/user/1000/libtmux/s"));
        ServerEndpoint indirect = ServerEndpoint.socketPath(Path.of("/run/user/1000/other/../libtmux/s"));

        assertEquals(direct, indirect);
        assertEquals(direct.hashCode(), indirect.hashCode());
        assertEquals(1, Set.copyOf(List.of(direct, indirect)).size(), "one socket is one key");
    }

    @Test
    void aRelativePathIsAnchoredSoItCanBeComparedAtAll() {
        ServerEndpoint relative = ServerEndpoint.socketPath(Path.of("build/fixture/s"));

        assertTrue(relative.flags().get(1).startsWith("/"), "a relative socket is ambiguous once logged");
    }

    @Test
    void differentKindsOfEndpointAreNeverEqual() {
        assertNotEquals(ServerEndpoint.defaultSocket(), ServerEndpoint.namedSocket("fixture"));
        assertNotEquals(ServerEndpoint.namedSocket("fixture"), ServerEndpoint.socketPath(Path.of("/tmp/fixture")));
        assertEquals(ServerEndpoint.defaultSocket(), ServerEndpoint.defaultSocket());
    }

    @Test
    void aSocketNameIsANameNotAPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerEndpoint.namedSocket("nested/fixture"),
                "tmux resolves -L under its own socket directory, so a separator cannot mean what it looks like");
        assertThrows(IllegalArgumentException.class, () -> ServerEndpoint.namedSocket(""));
    }

    /**
     * A socket path long enough to overflow {@code sun_path} is a real failure, but it is tmux's to
     * report: the limit is the target platform's, and the error is only actionable next to the
     * command that hit it. Constructing the value has to stay possible.
     */
    @Test
    void anOverlongSocketPathIsNotRejectedHere() {
        Path overlong = Path.of("/tmp", "x".repeat(4096));

        ServerEndpoint endpoint = ServerEndpoint.socketPath(overlong);

        assertEquals(2, endpoint.flags().size(), "the length check belongs at dispatch, not construction");
    }

    // ------------------------------------------------------------------------------ environment

    @Test
    void anEnvironmentThatNamesNoServerLeavesTheChoiceToTmux() {
        assertEquals(ServerEndpoint.defaultSocket(), ServerEndpoint.fromEnvironment(Map.of()));
    }

    /**
     * {@code TMUX} names the server of the pane this process runs in, and tmux follows it when it is
     * given no socket. Turning it into {@code -S} here would make "no socket" mean "the pane's
     * server" for every caller, which only {@link TmuxEnvironment#config()} is meant to say.
     */
    @Test
    void theTmuxVariableIsNotReadAsASocket() {
        Map<String, String> inside = Map.of("TMUX", "/tmp/libtmux-java-test/inside/s,1,0", "TMUX_PANE", "%0");

        assertEquals(ServerEndpoint.defaultSocket(), ServerEndpoint.fromEnvironment(inside));
    }

    @Test
    void libtmuxSocketSelectsAServerByName() {
        assertEquals(
                ServerEndpoint.namedSocket("work"), ServerEndpoint.fromEnvironment(Map.of("LIBTMUX_SOCKET", "work")));
    }

    @Test
    void libtmuxSocketPathSelectsAServerByPath() {
        assertEquals(
                ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-test/env/s")),
                ServerEndpoint.fromEnvironment(Map.of("LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s")));
    }

    /**
     * The Python libtmux builds {@code Server(socket_name, socket_path)} from the two variables, keeps
     * only the path when it is given both, and tmux ignores {@code -L} beside {@code -S} in any case.
     */
    @Test
    void thePathWinsWhenBothVariablesAreSet() {
        ServerEndpoint chosen = ServerEndpoint.fromEnvironment(
                Map.of("LIBTMUX_SOCKET", "work", "LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s"));

        assertEquals(List.of("-S", "/tmp/libtmux-java-test/env/s"), chosen.flags());
    }

    /** Each variable is judged alone, so a blank path does not hide a name that is set. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void aBlankVariableCountsAsUnsetOnItsOwn(String blank) {
        assertEquals(
                List.of("-L", "work"),
                ServerEndpoint.fromEnvironment(Map.of("LIBTMUX_SOCKET_PATH", blank, "LIBTMUX_SOCKET", "work"))
                        .flags());
        assertEquals(
                List.of("-S", "/tmp/libtmux-java-test/env/s"),
                ServerEndpoint.fromEnvironment(
                                Map.of("LIBTMUX_SOCKET_PATH", "/tmp/libtmux-java-test/env/s", "LIBTMUX_SOCKET", blank))
                        .flags());
        assertEquals(
                ServerEndpoint.defaultSocket(),
                ServerEndpoint.fromEnvironment(Map.of("LIBTMUX_SOCKET_PATH", blank, "LIBTMUX_SOCKET", blank)));
    }

    /**
     * The value is the operator's and the message goes to logs, so it names the variable and the way
     * forward and leaves what was in it out.
     */
    @Test
    void aSocketNameThatIsAPathIsRefusedWithoutEchoingIt() {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> ServerEndpoint.fromEnvironment(Map.of("LIBTMUX_SOCKET", "nested/distinctive")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("LIBTMUX_SOCKET"), message);
        assertTrue(message.contains("LIBTMUX_SOCKET_PATH"), message);
        assertFalse(message.contains("distinctive"), message);
    }
}
