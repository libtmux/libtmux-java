package com.git_pull.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.control.PaneOutput;
import com.git_pull.libtmux.junit5.TmuxExtension;
import com.git_pull.libtmux.junit5.TmuxSocketPath;
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
    void findingPanesSelectsOnWhatIsRunning(Server server, TmuxSocketPath socket) {
        // The fixture's pane runs a shell, so the shell's own name is the one thing certain to match.
        String running = server.panes().get(0).currentCommand();

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
}
