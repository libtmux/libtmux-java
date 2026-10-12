package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Every example, run against a real tmux.
 *
 * <p>Examples rot silently. They are the part of a project nobody compiles and
 * everybody reads first, so an API change breaks them without breaking anything
 * that would say so. These call the same method {@code main} calls, which is
 * why each example has one.
 */
@ExtendWith(TmuxExtension.class)
final class ExamplesRunTest {

    @Test
    void aWorkspaceIsLeftRunning(Server server, TmuxSocketPath socket) {
        String reported = BuildAWorkspace.run(socket.path());

        assertTrue(reported.startsWith("session work has "), reported);
        assertTrue(server.hasSession("work"), "the example leaves a session");
    }

    @Test
    void aWorkspaceStartsAMissingDaemon(Server server, TmuxSocketPath socket) {
        server.killServer();
        try {
            String reported = BuildAWorkspace.run(socket.path());

            assertTrue(reported.startsWith("session work has "), reported);
            assertTrue(server.hasSession("work"));
        } finally {
            server.killServer();
        }
    }

    @Test
    void findingPanesSelectsOnCommand(Server server, TmuxSocketPath socket) {
        // The fixture's pane runs a shell, so the shell's own name is the one
        // thing certain to match, once it has settled. While the shell starts,
        // tmux reports whatever its startup files are running, locale for one,
        // and a name read then matches nothing a moment later.
        Pane shell = server.panes().get(0);
        String running = settle(shell);
        Path path = socket.path();

        List<Pane> found = FindPanesRunning.run(path, running);

        assertFalse(found.isEmpty(), "nothing matched '" + running + "'");
        assertTrue(found.stream().allMatch(pane -> startsWith(pane, running)));
        assertEquals(List.of(), FindPanesRunning.run(path, "no-such-command"));
    }

    @Test
    void aCommandReportsItsExitStatus(Server server, TmuxSocketPath socket) {
        settle(server.panes().get(0));
        String command = "printf 'built\\n'; exit 3";

        String reported = report(socket.path(), command);

        assertEquals("exit 3, 1 line(s): built", reported);
    }

    /** Bash in CI switched bracketed paste off in front of the printed line. */
    @Test
    void aPrintedLineIsFoundBehindAModeSwitchButNotInTheTypedCommand() {
        String typed = "$ echo watched\r\n";
        String modeSwitch = "\u001B[?2004l\r";

        String output = typed + modeSwitch + "watched\r\n";

        assertTrue(WatchPaneOutput.printedLine(output, "watched"));
        assertFalse(WatchPaneOutput.printedLine(typed, "watched"));
    }

    @Test
    void watchingAPaneSeesWhatItPrints(TmuxSocketPath socket) {
        Duration deadline = Duration.ofSeconds(30);
        Path path = socket.path();
        var seen = WatchPaneOutput.run(path, deadline, output -> {});
        String why = "attaching makes tmux push output: " + seen;

        assertTrue(WatchPaneOutput.sawTheEcho(seen), why);
    }

    private static String report(Path path, String command) {
        try {
            return RunACommand.run(path, command);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static boolean startsWith(Pane pane, String running) {
        return pane.currentCommand().startsWith(running);
    }

    /**
     * The name of the command a pane is running, once it stops changing. A
     * shell that is still starting reports whatever its startup files run,
     * locale for one, and a name read then matches nothing a moment later, and
     * is not a shell {@code Pane.run} will type at.
     */
    private static String settle(Pane shell) {
        String[] previous = {shell.currentCommand()};
        try {
            shell.await(
                    fresh -> {
                        String now = fresh.currentCommand();
                        boolean same = now.equals(previous[0]);
                        previous[0] = now;
                        return same;
                    },
                    Duration.ofSeconds(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return previous[0];
    }

    @Test
    void servingOverMcpReportsTheFixedToolSurface(TmuxSocketPath socket) {
        List<String> tools = ServeTmuxOverMcp.run(socket.path());

        // The catalog holds 45. Teardown is enabled by default only for a
        // daemon this process created, and the fixture's server already
        // exists, so its four tools are not offered.
        String listed = tools.toString();
        assertEquals(41, tools.size(), listed);
        assertTrue(tools.contains("capture_pane"), listed);
        assertFalse(tools.contains("kill_session"), listed);
        assertEquals(tools.stream().sorted().toList(), tools, "a stable order");
    }

    @Test
    void watchingAServerIsToldWhenAWindowAppears(TmuxSocketPath socket) {
        Duration deadline = Duration.ofSeconds(30);
        Path path = socket.path();
        var seen = WatchWhatChanges.run(path, deadline, event -> {});
        String why = "tmux reports the difference: " + seen;

        assertTrue(WatchWhatChanges.sawTheNewWindow(seen), why);
    }
}
