package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.ArrayList;
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
     * The case a cursor exists to notice: the line it was anchored to is genuinely gone, so what
     * follows does not follow, and the answer says so rather than quietly stitching two unrelated
     * screens together.
     *
     * <p>The history is dropped through tmux rather than by running {@code clear} in the pane. What
     * the shell's own clear does to the scrollback is not the same across the supported range — on
     * 3.2a the cleared lines are still in history, so a reader that found them there and reported
     * continuity was right — and this is here to pin what this server promises, not what a terminal
     * happens to do with an escape sequence.
     */
    @Test
    void aPaneWhoseHistoryIsGoneIsReportedAsADiscontinuity(Server server) {
        String pane = server.panes().get(0).id().value();
        run(server, pane, "echo before-the-clear");
        String cursor = Reading.since(TestCalls.on(server, "pane_id", pane)).cursor();

        run(server, pane, "clear");
        server.cmd("clear-history", "-t", pane);
        run(server, pane, "echo after-the-clear");

        Reading.Since fresh = Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", cursor));

        assertFalse(fresh.continuous(), "the pane no longer follows on from where the cursor was");
        assertTrue(String.valueOf(fresh.note()).contains("does not follow on"), String.valueOf(fresh.note()));
        assertTrue(
                fresh.content().stream().anyMatch(line -> line.contains("after-the-clear")),
                "and what it does show is what the pane shows now: " + fresh.content());
    }

    /**
     * A pane that is scrolling is still continuous, and this is the case that says so.
     *
     * <p>Where a line sits in a capture depends on how far the pane has scrolled, so a capture and a
     * position read as two tmux invocations can disagree — and a pane that merely scrolled between
     * them then looks exactly like a pane that was cleared. Measured before the reads were batched,
     * this reported two false discontinuities in forty; it must now report none, because a model
     * told its cursor is broken will start again and re-read everything.
     */
    @Test
    void aPaneScrollingUnderTheReaderIsNeverMistakenForAClearedOne(Server server) throws Exception {
        // A history far larger than this can fill, so output outrunning it — a real discontinuity —
        // cannot be what this measures. Set before the window exists, because the limit a pane keeps
        // is the one in force when it was made.
        server.globalOptions().set("history-limit", "20000");
        var window = server.sessions().get(0).newWindow("scrolling");
        String pane = window.panes().get(0).id().value();
        // Short, so every line of output scrolls it, and fast enough that lines land between two
        // tmux invocations — which is exactly the gap this is here to prove is closed.
        server.cmd("resize-window", "-t", window.id().value(), "-x", "80", "-y", "10");
        server.run(List.of(
                "send-keys",
                "-l",
                "-t",
                pane,
                "for r in $(seq 1 200); do for i in $(seq 1 30); do echo line-$r-$i; done; sleep 0.01; done"));
        server.run(List.of("send-keys", "-t", pane, "Enter"));
        Thread.sleep(300);

        String cursor = Reading.since(TestCalls.on(server, "pane_id", pane)).cursor();
        List<String> broken = new ArrayList<>();
        for (int attempt = 0; attempt < 60; attempt++) {
            Reading.Since since = Reading.since(TestCalls.on(server, "pane_id", pane, "cursor", cursor));
            cursor = since.cursor();
            if (!since.continuous()) {
                broken.add(String.valueOf(since.note()));
            }
            Thread.sleep(10);
        }

        assertEquals(List.of(), broken, "a scrolling pane was reported as a discontinuity");
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
