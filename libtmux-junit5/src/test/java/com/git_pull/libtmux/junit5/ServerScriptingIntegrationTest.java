package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.TmuxVersion;
import com.git_pull.libtmux.UnsupportedTmuxVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asking the server to run things and to say what it knows.
 *
 * <p>{@code run-shell} reports its command's output on 3.2a, loses it in 3.3a and 3.4, and reports it
 * again from 3.5. That is a hole in the middle of the range rather than a floor, so both branches
 * assert here and the version arithmetic is spelled out rather than assumed.
 */
@ExtendWith(TmuxExtension.class)
final class ServerScriptingIntegrationTest {

    private static final TmuxVersion LOST = new TmuxVersion(3, 3, "");
    private static final TmuxVersion FOUND = new TmuxVersion(3, 5, "");

    private static boolean losesShellOutput(Server server) {
        TmuxVersion running = server.version();
        return running.atLeast(LOST) && !running.atLeast(FOUND);
    }

    // -------------------------------------------------------------------------------- expanding

    @Test
    void theServerExpandsFormatsThatBelongToNoSession(Server server) {
        assertEquals(server.version().toString(), server.expand("#{version}"));
        assertTrue(Long.parseLong(server.expand("#{pid}")) > 0, "the server has a process id");
    }

    @Test
    void aFormatThatMeansNothingComesBackEmpty(Server server) {
        assertEquals("", server.expand("#{no_such_field_at_all}"));
    }

    // ------------------------------------------------------------------------------- run-shell

    /** The effect happens on every release, whatever the release says about the output. */
    @Test
    void aShellCommandRunsForItsEffectOnEveryRelease(Server server, @TempDir Path directory) throws Exception {
        Path touched = directory.resolve("ran");

        server.runShell("touch " + touched);

        assertTrue(await(() -> Files.exists(touched)), "the command never ran");
    }

    @Test
    void readingWhatTheCommandPrintedWorksOrRefuses(Server server) {
        if (losesShellOutput(server)) {
            UnsupportedTmuxVersion refused =
                    assertThrows(UnsupportedTmuxVersion.class, () -> server.runShellCapturing("echo captured-me"));

            assertTrue(
                    String.valueOf(refused.getMessage()).contains("run-shell"),
                    "the refusal says what is missing: " + refused.getMessage());
        } else {
            List<String> printed = server.runShellCapturing("echo captured-me");

            assertTrue(printed.contains("captured-me"), "what came back: " + printed);
        }
    }

    /**
     * A refusal must not be a silent one: the releases that lose the output are exactly 3.3a and
     * 3.4, and every other lane has to take the capturing path.
     */
    @Test
    void exactlyTheTwoBrokenReleasesRefuse(Server server) {
        String lane = System.getProperty("libtmux.tmux.expected");
        if (lane == null) {
            return; // not a matrix lane; the ordinary suite runs whichever tmux is on PATH
        }

        boolean expectedToRefuse = List.of("3.3a", "3.4").contains(lane);

        assertEquals(expectedToRefuse, losesShellOutput(server), "lane " + lane + " disagrees with the version rule");
    }

    // --------------------------------------------------------------------------- list-commands

    @Test
    void theServerListsTheCommandsItKnows(Server server) {
        List<String> commands = server.listCommands();

        assertTrue(commands.size() > 50, "a tmux knows many commands, not " + commands.size());
        assertTrue(commands.stream().anyMatch(line -> line.startsWith("new-session")), "new-session is not among them");
        assertTrue(
                commands.stream().anyMatch(line -> line.startsWith("split-window")), "split-window is not among them");
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
