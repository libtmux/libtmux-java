package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restarting what a pane runs, watching what it prints, and asking tmux things a snapshot does not
 * carry.
 *
 * <p>None of these commands changed a flag between 3.2a and 3.7b, so there is no version rule here —
 * only behaviour, checked on whichever release the lane runs.
 */
@ExtendWith(TmuxExtension.class)
final class PaneProcessIntegrationTest {

    /** {@code keepOnExit} ({@code split-window -k}), which a dead-and-kept pane needs here, requires tmux 3.7. */
    private static final TmuxVersion REMAIN_ON_EXIT_SINCE = new TmuxVersion(3, 7, "");

    /** tmux 3.8 stopped reporting a dead pane's stale {@code #{pane_pid}}. */
    private static final TmuxVersion EMPTY_DEAD_PID_SINCE = new TmuxVersion(3, 8, "");

    // ------------------------------------------------------------------------------- dead panes

    /**
     * JAVA-3: {@code pid()} for a dead, kept pane is version-dependent, and there was no way to ask
     * "is this pane dead" short of a raw {@code #{pane_dead}} round trip. {@code dead()} is that
     * accessor, read live rather than from the capture.
     */
    @Test
    void deadReadsLiveAndADeadPanesPidGoesEmptyFromTmux38(Server server) throws InterruptedException {
        Pane original = onlyPane(server);
        if (!server.version().atLeast(REMAIN_ON_EXIT_SINCE)) {
            assertFalse(original.dead(), "a live pane on an ordinary shell is not dead");
            return;
        }

        Pane kept = original.split(s -> s.keepOnExit().running("true"));
        assertTrue(Await.until(() -> kept.refresh().dead()), "the kept pane never reported dead");

        if (server.version().atLeast(EMPTY_DEAD_PID_SINCE)) {
            assertTrue(kept.refresh().pid().isEmpty(), "tmux 3.8 and later report #{pane_pid} empty here");
        } else {
            assertTrue(kept.refresh().pid().isPresent(), "a released tmux keeps the exited process's stale pid");
        }
    }

    // -------------------------------------------------------------------------------- expanding

    @Test
    void aFormatReachesWhatTheSnapshotDoesNotCarry(Server server) {
        Pane pane = onlyPane(server);

        assertEquals(Integer.toString(pane.index()), pane.expand("#{pane_index}"));
        assertEquals(pane.id().value(), pane.expand("#{pane_id}"));
        assertEquals(Long.toString(pane.pid().orElseThrow()), pane.expand("#{pane_pid}"));
    }

    @Test
    void severalFieldsExpandTogetherIntoOneAnswer(Server server) {
        Pane pane = onlyPane(server);

        String where = pane.expand("#{window_index}.#{pane_index}");

        assertEquals(pane.window().index().value() + "." + pane.index(), where);
    }

    @Test
    void aFormatThatMeansNothingHereComesBackEmpty(Server server) {
        assertEquals("", onlyPane(server).expand("#{pane_no_such_field}"));
    }

    // -------------------------------------------------------------------------------- respawning

    /**
     * tmux refuses to respawn a pane that is still running something, so the killing form is the
     * only one offered. A caller asking to respawn wants the process replaced.
     */
    @Test
    void respawningReplacesTheProcessInThePane(Server server) throws InterruptedException {
        Pane pane = onlyPane(server);
        long before = pane.pid().orElseThrow();

        pane.respawn();

        assertTrue(
                Await.until(() -> pane.refresh().pid().orElseThrow() != before),
                "the pane kept process " + before + " through a respawn");
        assertEquals(pane.id(), pane.refresh().id(), "and it is still the same pane");
    }

    @Test
    void respawningWithACommandRunsThatCommand(Server server) throws InterruptedException {
        Pane pane = onlyPane(server);

        pane.respawn("sleep", "30");

        assertTrue(
                Await.until(() -> "sleep".equals(pane.refresh().currentCommand())),
                "the pane never reported the command it was respawned with");
    }

    /**
     * Writing {@code respawn()} reaches the no-argument form, so this is not something a call site
     * trips over by hand. It is reachable when the command is assembled at run time and turns out
     * empty, which would otherwise become a bare respawn of the default command — a different thing
     * from what the caller asked for.
     */
    @Test
    void respawningWithACommandThatTurnedOutEmptyIsRefused(Server server) {
        Pane pane = onlyPane(server);
        String[] assembled = new String[0];

        assertThrows(IllegalArgumentException.class, () -> pane.respawn(assembled));
    }

    // ----------------------------------------------------------------------------------- piping

    @Test
    void aPipedPaneSendsWhatItPrintsToTheCommand(Server server, @TempDir Path directory) throws Exception {
        Path captured = directory.resolve("piped");
        Pane pane = onlyPane(server);

        pane.pipeTo("cat > " + captured);
        pane.sendLine("echo piped-marker");

        assertTrue(Await.until(() -> contains(captured, "piped-marker")), "nothing reached the pipe");
    }

    @Test
    void stoppingThePipeStopsTheOutput(Server server, @TempDir Path directory) throws Exception {
        Path captured = directory.resolve("piped");
        Pane pane = onlyPane(server);

        pane.pipeTo("cat > " + captured);
        pane.sendLine("echo before-stop");
        assertTrue(Await.until(() -> contains(captured, "before-stop")), "the pipe never started");

        pane.stopPiping();
        pane.sendLine("echo after-stop");
        Thread.sleep(600);

        assertTrue(!contains(captured, "after-stop"), "output kept arriving after the pipe was stopped");
    }

    @Test
    void stoppingAPipeThatWasNeverStartedIsNotAFailure(Server server) {
        onlyPane(server).stopPiping();

        onlyPane(server).stopPiping();
    }

    @Test
    void aSecondPipeReplacesTheFirstRatherThanAddingToIt(Server server, @TempDir Path directory) throws Exception {
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        Pane pane = onlyPane(server);

        pane.pipeTo("cat > " + first);
        pane.pipeTo("cat > " + second);
        pane.sendLine("echo only-once");

        assertTrue(Await.until(() -> contains(second, "only-once")), "the second pipe never received anything");
        assertTrue(!contains(first, "only-once"), "tmux keeps one pipe per pane, not a list");
        assertNotEquals(first, second);
    }

    // -------------------------------------------------------------------------------- helpers

    private static Pane onlyPane(Server server) {
        return server.sessions().get(0).windows().get(0).panes().get(0);
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.exists(file) && Files.readString(file).contains(text);
        } catch (IOException e) {
            return false;
        }
    }
}
