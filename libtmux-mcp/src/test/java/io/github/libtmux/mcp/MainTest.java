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
 * Which server the launcher was told to expose. A client passes these once and never sees them
 * again, so a misread flag hands the model a working server that is not the one it named.
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

    /** Both spellings are documented, so both can be given. Last wins, as a shell caller expects. */
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

    /**
     * The ceiling decides which tools exist at all, so a launcher that quietly ignored the flag would
     * hand a model the power to kill things its operator meant to withhold.
     */
    @Test
    void theSafetyCeilingIsReadFromTheFlag() {
        assertEquals(Safety.READONLY, Main.safety(List.of("--safety", "readonly")));
        assertEquals(Safety.DESTRUCTIVE, Main.safety(List.of("--socket-name", "work", "--safety", "destructive")));
    }

    /** Left unsaid, a server reads and changes tmux but cannot destroy anything. */
    @Test
    void theCeilingLeftUnsaidStopsShortOfDestroying() {
        assertEquals(Safety.MUTATING, Main.safety(List.of("--socket-name", "work")));
    }

    @Test
    void aCeilingNobodyRecognisesStopsTheLauncher() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Main.safety(List.of("--safety", "yolo")));

        assertTrue(String.valueOf(refused.getMessage()).contains("readonly"), String.valueOf(refused.getMessage()));
    }

    /** The endpoint parser has to know the flag exists, or it would reject a launch that is correct. */
    @Test
    void theSafetyFlagDoesNotConfuseTheEndpointParser() {
        ServerConfig config = Main.configure(List.of("--safety", "readonly", "--socket-name", "work"));

        assertEquals(ServerEndpoint.namedSocket("work"), config.endpoint());
    }
}
