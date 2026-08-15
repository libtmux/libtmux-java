package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
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

    // -------------------------------------------------------------------------------- expanding

    @Test
    void aFormatReachesWhatTheSnapshotDoesNotCarry(Server server) {
        Pane pane = onlyPane(server);

        assertEquals(Integer.toString(pane.index()), pane.expand("#{pane_index}"));
        assertEquals(pane.id().value(), pane.expand("#{pane_id}"));
        assertEquals(Long.toString(pane.pid()), pane.expand("#{pane_pid}"));
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
        long before = pane.pid();

        pane.respawn();

        assertTrue(
                await(() -> pane.refresh().pid() != before), "the pane kept process " + before + " through a respawn");
        assertEquals(pane.id(), pane.refresh().id(), "and it is still the same pane");
    }

    @Test
    void respawningWithACommandRunsThatCommand(Server server) throws InterruptedException {
        Pane pane = onlyPane(server);

        pane.respawn("sleep", "30");

        assertTrue(
                await(() -> "sleep".equals(pane.refresh().currentCommand())),
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

        assertTrue(await(() -> contains(captured, "piped-marker")), "nothing reached the pipe");
    }

    @Test
    void stoppingThePipeStopsTheOutput(Server server, @TempDir Path directory) throws Exception {
        Path captured = directory.resolve("piped");
        Pane pane = onlyPane(server);

        pane.pipeTo("cat > " + captured);
        pane.sendLine("echo before-stop");
        assertTrue(await(() -> contains(captured, "before-stop")), "the pipe never started");

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

        assertTrue(await(() -> contains(second, "only-once")), "the second pipe never received anything");
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
