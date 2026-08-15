package com.git_pull.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
