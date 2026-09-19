package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Dimensions;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.TextOutcome;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What {@code awaitText} must not mistake for the pane's answer.
 *
 * <p>Reading a screen is a heuristic, and two things make it the wrong answer rather than a late
 * one. A terminal echoes what was typed at it, so a wait for text the command's own line contains
 * is satisfied before the command has done anything. And a terminal breaks a long line at the
 * pane's width, so text wider than the pane is in no single row and a wait watching rows never sees
 * what is in plain view.
 *
 * <p>Each pane here runs a plain shell with a two-character prompt at a stated width. A login
 * shell's own prompt is long enough to wrap the command being typed, which hides the first failure
 * behind the second.
 */
@ExtendWith(TmuxExtension.class)
final class PaneWaitFidelityIntegrationTest {

    /**
     * Sized by resizing the window rather than the session: a detached session cannot be given a
     * size before tmux 3.3, and {@code resize-pane} does not move a lone pane in a detached session
     * at all — measured on 3.2a, where the width stayed 80 — while {@code resize-window} does.
     */
    private static Pane shell(Server server, String name, int columns) throws InterruptedException {
        Window window = server.newSession(session -> session.named(name).running("env", "PS1=$ ", "ENV=", "/bin/sh"))
                .windows()
                .get(0);
        window.resizeTo(new Dimensions(columns, 24));
        Pane pane = window.refresh().panes().get(0);
        assertNotEquals(TextOutcome.TIMED_OUT, pane.awaitText("$", Duration.ofSeconds(10)), "the shell drew a prompt");
        return pane;
    }

    @Test
    void aCommandsOwnEchoIsNotItsOutput(Server server) throws InterruptedException {
        Pane pane = shell(server, "echo", 120);

        pane.sendLine("sleep 5; echo build-finished");
        long started = System.nanoTime();
        TextOutcome why = pane.awaitText("build-finished", Duration.ofSeconds(3));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(
                TextOutcome.TIMED_OUT,
                why,
                "the only thing on screen is the echo of the command, which cannot print for five seconds");
        assertTrue(millis >= 2_500, "and the wait really waited rather than matching at once: " + millis + "ms");
    }

    @Test
    void whatTheCommandActuallyPrintsStillMatches(Server server) throws InterruptedException {
        Pane pane = shell(server, "output", 120);

        // Printed a second late, so the wait has to see it arrive rather than find it waiting. The
        // command's own text contains the marker, so this fails either way round: if the echo were
        // matched the answer would come back before the second was up, and if suppressing the echo
        // also suppressed the output it would never come back at all.
        pane.sendLine("sleep 1; printf 'build-finished\\n'");

        assertEquals(
                TextOutcome.APPEARED,
                pane.awaitText("build-finished", Duration.ofSeconds(10)),
                "suppressing the echo must not suppress the output, which here is the same text");
    }

    @Test
    void textTheTerminalBrokeAcrossRowsIsFound(Server server) throws InterruptedException {
        Pane pane = shell(server, "wrap", 30);
        String marker = "MARK-0123456789-ABCDEFGHIJ-0123456789-END";

        // Assembled from two halves, so the command's own text never contains the marker whole and
        // this cannot pass on the echo.
        pane.sendLine("sleep 1; printf '%s%s\\n' MARK-0123456789-ABCDE FGHIJ-0123456789-END");

        assertEquals(
                TextOutcome.APPEARED,
                pane.awaitText(marker, Duration.ofSeconds(10)),
                "the marker is 41 characters in a 30-column pane, so no row holds all of it");
    }

    /**
     * A command's output routinely names the command: {@code make} answers {@code make: ... Stop.}.
     * Taking the echo out by dropping every row that mentions it hid the answer along with the
     * question, and the wait timed out with the answer on screen.
     */
    @Test
    void outputThatNamesTheCommandThatProducedItIsFound(Server server) throws InterruptedException {
        Pane pane = shell(server, "names", 120);

        pane.sendLine("make");

        // Found at all is the whole point: make answers in the same breath, so whether the answer
        // was already there on the first look says nothing, while timing out would mean the rule
        // that takes the echo out had taken the answer with it.
        assertNotEquals(
                TextOutcome.TIMED_OUT,
                pane.awaitText("Stop.", Duration.ofSeconds(10)),
                "make prints a message naming itself, and that message is the pane's answer");
    }

    /**
     * Text an earlier run left on screen is not this run's output. Reported rather than ignored,
     * because timing out while {@code capture} shows the text is worse than saying it was there.
     */
    @Test
    void textLeftByAnEarlierRunIsNotReportedAsHavingAppeared(Server server) throws InterruptedException {
        Pane pane = shell(server, "entry", 120);

        pane.sendLine("sleep 1; printf 'tests-passed\\n'");
        assertEquals(TextOutcome.APPEARED, pane.awaitText("tests-passed", Duration.ofSeconds(10)));

        assertEquals(
                TextOutcome.PRESENT_AT_ENTRY,
                pane.awaitText("tests-passed", Duration.ofSeconds(2)),
                "the second wait began with the first run's output already on screen");
    }

    @Test
    void captureItselfStillReportsRowsAsTheyAreDisplayed(Server server) throws InterruptedException {
        Pane pane = shell(server, "rows", 30);

        pane.sendLine("printf '%s%s\\n' MARK-0123456789-ABCDE FGHIJ-0123456789-END");
        assertNotEquals(
                TextOutcome.TIMED_OUT,
                pane.awaitText("MARK-0123456789-ABCDEFGHIJ-0123456789-END", Duration.ofSeconds(10)));

        List<String> rows = pane.capture();

        assertTrue(
                rows.stream().anyMatch(row -> row.contains("MARK-0123456789-ABCDEFGHIJ-012")),
                "capture answers with what the pane displays; only the wait rejoins rows: " + rows);
        assertTrue(
                rows.stream().noneMatch(row -> row.contains("MARK-0123456789-ABCDEFGHIJ-0123456789-END")),
                "so no single row holds the whole marker: " + rows);
    }
}
