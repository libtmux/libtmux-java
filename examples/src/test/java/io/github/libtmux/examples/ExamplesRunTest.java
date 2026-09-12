package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.PaneOutput;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Every example, run against a real tmux.
 *
 * <p>Examples rot silently. They are the part of a project nobody compiles and everybody reads
 * first, so an API change breaks them without breaking anything that would say so. These call the
 * same method {@code main} calls, which is why each example has one.
 */
@ExtendWith(TmuxExtension.class)
final class ExamplesRunTest {

    @Test
    void buildingAWorkspaceLeavesOneBehind(Server server, TmuxSocketPath socket) {
        String reported = BuildAWorkspace.run(socket.path());

        assertTrue(reported.startsWith("session work has "), reported);
        assertTrue(server.hasSession("work"), "the example is supposed to leave a session running");
    }

    @Test
    void findingPanesSelectsOnWhatIsRunning(Server server, TmuxSocketPath socket) throws InterruptedException {
        // The fixture's pane runs a shell, so the shell's own name is the one thing certain to match —
        // once it has settled. While the shell starts, tmux reports whatever its startup files are
        // running, locale for one, and a name read then matches nothing a moment later.
        Pane shell = server.panes().get(0);
        String[] previous = {shell.currentCommand()};
        shell.await(
                fresh -> {
                    boolean settled = fresh.currentCommand().equals(previous[0]);
                    previous[0] = fresh.currentCommand();
                    return settled;
                },
                Duration.ofSeconds(10));
        String running = previous[0];

        List<Pane> found = FindPanesRunning.run(socket.path(), running);

        assertFalse(found.isEmpty(), "nothing matched '" + running + "'");
        assertTrue(found.stream().allMatch(pane -> pane.currentCommand().startsWith(running)));
        assertEquals(List.of(), FindPanesRunning.run(socket.path(), "no-such-command-anywhere"));
    }

    @Test
    void watchingAPaneSeesWhatItPrints(TmuxSocketPath socket) {
        List<PaneOutput> seen = WatchPaneOutput.run(socket.path(), Duration.ofSeconds(30), output -> {});

        assertFalse(seen.isEmpty(), "attaching is what makes tmux push output, and none arrived");
    }

    @Test
    void servingOverMcpReportsTheFixedToolSurface(TmuxSocketPath socket) {
        List<String> tools = ServeTmuxOverMcp.run(socket.path());

        // The catalog holds 45. Teardown is enabled by default only for a daemon this process
        // created, and the fixture's server already exists, so its four tools are not offered.
        assertEquals(41, tools.size(), tools.toString());
        assertTrue(tools.contains("capture_pane"), tools.toString());
        assertFalse(tools.contains("kill_session"), "teardown reached a server the example did not start: " + tools);
        assertEquals(tools.stream().sorted().toList(), tools, "the example reports a stable order");
    }

    @Test
    void watchingAServerIsToldWhenAWindowAppears(TmuxSocketPath socket) {
        List<ControlEvent> seen = WatchWhatChanges.run(socket.path(), Duration.ofSeconds(30), event -> {});

        assertTrue(
                WatchWhatChanges.sawTheNewWindow(seen),
                "tmux compares a watched format itself and reports the difference: " + seen);
    }
}
