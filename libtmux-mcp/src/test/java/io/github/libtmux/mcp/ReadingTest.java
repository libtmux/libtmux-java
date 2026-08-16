package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Reading a pane, and reading it again without paying for it twice.
 *
 * <p>The cursor is the whole point of watching something over several turns, and the case that
 * matters is the one where it can no longer be trusted: a pane cleared, or output that has outrun
 * the history tmux keeps. Handing back lines that do not follow the ones before them, without
 * saying so, is worse than handing back nothing.
 */
@ExtendWith(TmuxExtension.class)
final class ReadingTest {

    @Test
    void aCaptureComesBackWithACursorForWatchingFromHere(Server server) {
        String pane = server.panes().get(0).id().value();

        Reading.Captured captured = Reading.capture(TestCalls.on(server, "pane_id", pane));

        assertEquals(pane, captured.paneId());
        assertFalse(captured.cursor().isEmpty(), "a capture always says where to carry on from");
        assertEquals(pane, Cursor.decode(captured.cursor()).paneId());
    }

    /**
     * A pane that has stopped changing costs nothing to watch. Caught up once, the next call brings
     * back no lines at all rather than the screen over again — which is the whole reason to hold a
     * cursor instead of calling tmux_capture_pane repeatedly.
     */
    @Test
    void aPaneThatHasStoppedChangingCostsNothingToWatchAgain(Server server) {
        String pane = server.panes().get(0).id().value();
        run(server, pane, "echo first-thing");
        String cursor = settled(server, pane);

        Reading.Since again = Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", cursor));

        assertEquals(List.of(), again.content(), "the screen was already delivered");
        assertTrue(again.continuous());
        assertEquals("Nothing new since the last call.", again.note());
    }

    /**
     * Reads until the pane stops producing lines and answers the cursor that reached that point.
     *
     * <p>A command's own output is not the last thing a pane draws: the shell redraws its prompt
     * afterwards, and tmux_run returns on the completion signal rather than waiting for that. So a
     * cursor taken the instant a command finishes legitimately has one more line coming.
     */
    private static String settled(Server server, String pane) {
        String cursor = Reading.since(TestCalls.on(server, "pane_id", pane)).cursor();
        int quiet = 0;
        for (int attempt = 0; attempt < 60 && quiet < 4; attempt++) {
            Reading.Since since = Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", cursor));
            cursor = since.cursor();
            // Several quiet reads, not one: the first can land in the gap between the command
            // finishing and the shell drawing its prompt, when the pane is only briefly still.
            quiet = since.content().isEmpty() ? quiet + 1 : 0;
            sleep();
        }
        return cursor;
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void onlyTheLinesAddedSinceTheCursorComeBack(Server server) {
        String pane = server.panes().get(0).id().value();
        run(server, pane, "echo already-seen");
        String cursor = Reading.since(TestCalls.on(server, "pane_id", pane)).cursor();
        run(server, pane, "echo brand-new");

        Reading.Since fresh = Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", cursor));

        assertTrue(fresh.continuous());
        assertTrue(
                fresh.content().stream().anyMatch(line -> line.contains("brand-new")),
                fresh.content().toString());
        assertTrue(
                fresh.content().stream().noneMatch(line -> line.contains("already-seen")),
                "what was already delivered must not be delivered again: " + fresh.content());
        assertNotEquals(cursor, fresh.cursor(), "the cursor advances");
    }

    /**
     * The case a cursor exists to notice. After the screen is cleared, the line the cursor was
     * anchored to is gone, so what follows does not follow — and the answer has to say so rather than
     * quietly stitching two unrelated screens together.
     */
    @Test
    void aClearedPaneIsReportedAsADiscontinuityRatherThanStitchedOn(Server server) {
        String pane = server.panes().get(0).id().value();
        run(server, pane, "echo before-the-clear");
        String cursor = Reading.since(TestCalls.on(server, "pane_id", pane)).cursor();
        run(server, pane, "clear");
        run(server, pane, "echo after-the-clear");

        Reading.Since fresh = Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", cursor));

        assertFalse(fresh.continuous(), "the pane no longer follows on from where the cursor was");
        assertTrue(String.valueOf(fresh.note()).contains("does not follow on"), String.valueOf(fresh.note()));
    }

    @Test
    void aCursorFromAnotherPaneIsRefusedRatherThanRead(Server server) {
        String first = server.panes().get(0).id().value();
        String second = server.sessions().get(0).windows().get(0).split().id().value();
        String cursor = Reading.since(TestCalls.on(server, "pane_id", first)).cursor();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> Reading.since(TestCalls.on(server, "pane_id", second, "cursor", cursor)));

        assertTrue(String.valueOf(refused.getMessage()).contains(first), refused.getMessage());
    }

    @Test
    void aCursorThisServerNeverIssuedSaysHowToStartAgain(Server server) {
        String pane = server.panes().get(0).id().value();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", "not-a-cursor")));

        assertTrue(String.valueOf(refused.getMessage()).contains("omit 'cursor'"), refused.getMessage());
    }

    @Test
    void aCaptureIsCappedAndSaysWhatItDropped(Server server) {
        String pane = server.panes().get(0).id().value();
        run(server, pane, "seq 1 60");

        Reading.Captured captured =
                Reading.capture(TestCalls.on(server, "pane_id", pane, "history", true, "max_lines", 4));

        assertEquals(4, captured.content().size());
        assertTrue(captured.truncated());
        assertTrue(String.valueOf(captured.note()).contains("most recent"), String.valueOf(captured.note()));
    }

    @Test
    void searchingFindsThePaneShowingSomething(Server server) {
        String first = server.panes().get(0).id().value();
        String second =
                server.sessions().get(0).newWindow("other").panes().get(0).id().value();
        run(server, second, "echo the-needle-is-here");

        Reading.Found found = Reading.search(TestCalls.on(server, "pattern", "the-needle-is-here"));

        assertTrue(found.count() >= 1, "the pane showing it must be found");
        assertTrue(
                found.matches().stream().anyMatch(hit -> hit.paneId().equals(second)),
                found.matches().toString());
        assertNotEquals(first, second);
    }

    @Test
    void searchingForSomethingNobodyShowsSaysWhereItDidNotLook(Server server) {
        Reading.Found found = Reading.search(TestCalls.on(server, "pattern", "nothing-shows-this-anywhere"));

        assertEquals(0, found.count());
        assertTrue(
                String.valueOf(found.note()).contains("history"),
                "an empty result has to say what it did not look at: " + found.note());
    }

    /** A model sending "[FAILED]" means those characters, not a character class. */
    @Test
    void aPatternIsPlainTextUnlessAskedToBeAnExpression(Server server) {
        String pane = server.panes().get(0).id().value();
        run(server, pane, "echo '[FAILED] the build'");

        Reading.Found literal = Reading.search(TestCalls.on(server, "pattern", "[FAILED]"));

        assertTrue(literal.count() >= 1, "plain text matched the brackets themselves");
    }

    private static void run(Server server, String pane, String command) {
        RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", command, "timeout", 15));
    }
}
