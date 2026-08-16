package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which tmux server the launcher was told to expose.
 *
 * <p>An MCP client launches this as a subprocess and never sees the arguments again, so a flag
 * misread here is invisible: the model gets a working server that is not the one the user named.
 * No tmux is started — the question is entirely what a list of strings becomes, and answering it
 * without a server is what keeps these cases able to cover the refusals too.
 */
final class MainTest {

    @Test
    void nothingSaidLeavesTmuxToFindItsOwnServer() {
        ServerConfig config = Main.configure(List.of());

        assertEquals(ServerEndpoint.defaultSocket(), config.endpoint());
        assertEquals("tmux", config.binary());
    }

    @Test
    void aSocketPathIsTakenAsAPath() {
        ServerConfig config = Main.configure(List.of("--socket", "/tmp/libtmux-java-dev/probe/s"));

        assertEquals(ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/probe/s")), config.endpoint());
    }

    /** A name is not a path: tmux resolves it under its own directory, which is the whole difference. */
    @Test
    void aSocketNameIsTakenAsAName() {
        ServerConfig config = Main.configure(List.of("--socket-name", "work"));

        assertEquals(ServerEndpoint.namedSocket("work"), config.endpoint());
    }

    @Test
    void theBinaryCanBeChosenAlongsideTheServer() {
        ServerConfig config = Main.configure(List.of("--socket-name", "work", "--tmux", "/usr/local/bin/tmux"));

        assertEquals(ServerEndpoint.namedSocket("work"), config.endpoint());
        assertEquals("/usr/local/bin/tmux", config.binary());
    }

    /**
     * The README shows both spellings of the endpoint, so nothing stops a caller giving both. Last
     * wins, which is the shell convention and the only one that makes a wrapper script's appended
     * flag able to override what it wrapped.
     */
    @Test
    void theLastEndpointNamedIsTheOneUsed() {
        ServerConfig config =
                Main.configure(List.of("--socket", "/tmp/libtmux-java-dev/probe/s", "--socket-name", "work"));

        assertEquals(ServerEndpoint.namedSocket("work"), config.endpoint());
    }

    @Test
    void aFlagNobodyRecognisesStopsTheLauncherRatherThanBeingIgnored() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Main.configure(List.of("--sokcet", "/tmp/s")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("--sokcet"), message);
    }

    /**
     * A flag whose value is missing would otherwise swallow the next flag as its value, and the
     * launcher would serve a server nobody asked for.
     */
    @Test
    void aFlagWithNoValueSaysWhichFlagIsMissingIt() {
        for (String flag : List.of("--socket", "--socket-name", "--tmux")) {
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> Main.configure(List.of(flag)));

            String message = String.valueOf(refused.getMessage());
            assertTrue(message.contains(flag), message);
        }
    }

    /** A value that looks like a flag is still a value; only its position decides. */
    @Test
    void aValueIsTakenLiterallyEvenWhenItLooksLikeAFlag() {
        ServerConfig config = Main.configure(List.of("--socket-name", "--tmux"));

        assertEquals(ServerEndpoint.namedSocket("--tmux"), config.endpoint());
    }
}
