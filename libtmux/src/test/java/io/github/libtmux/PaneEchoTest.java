package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which characters a wait stops seeing when this library typed them.
 *
 * <p>An echo is taken out where it stands the way an echo stands — a whole run of the typed text,
 * ending its line — because a command's own output routinely names the command, and a rule that took
 * out every line mentioning it hid the answer along with the question.
 */
final class PaneEchoTest {

    /**
     * The defect this pins: {@code make} answered {@code make: ... Stop.} and the whole row was
     * dropped, so the wait timed out with the answer on screen. The row stays; the word that repeats
     * the command goes with the echo, which is the documented cost.
     */
    @Test
    void theAnswerSurvivesWhenItNamesTheCommand() {
        List<String> shown =
                PaneEcho.withoutEcho(List.of("$ make", "make: *** No targets specified.  Stop.", "$ "), "make");

        assertEquals(List.of("$ ", ": *** No targets specified.  Stop.", "$ "), shown);
    }

    /** zsh draws {@code %} straight after what was typed; an echo is still an echo. */
    @Test
    void anEchoTheShellDrewSomethingStraightAfterIsStillTakenOut() {
        List<String> shown = PaneEcho.withoutEcho(List.of("(sleep 1; echo seen) &%", "seen"), "(sleep 1; echo seen) &");

        assertEquals(List.of("%", "seen"), shown);
    }

    /** A word the command's output merely begins with is not the command. */
    @Test
    void anEchoThatBeginsAnotherWordIsLeftInThatWord() {
        List<String> shown = PaneEcho.withoutEcho(List.of("$ make", "makefile: not found"), "make");

        assertEquals(List.of("$ ", "makefile: not found"), shown);
    }

    /** {@code id} is inside {@code uid=}, so a rule matching anywhere took the whole answer out. */
    @Test
    void anEchoThatIsASubstringOfItsOutputTakesOnlyItself() {
        List<String> shown = PaneEcho.withoutEcho(List.of("$ id", "uid=1000(d) gid=1000(d)"), "id");

        assertEquals(List.of("$ ", "uid=1000(d) gid=1000(d)"), shown);
    }

    /**
     * {@code ls} ends the word {@code tools} at the end of a line, which is exactly where an echo ends
     * too — only the start of it tells them apart, so a listing keeps its last file name.
     */
    @Test
    void anEchoThatEndsAnotherWordIsLeftInThatWord() {
        List<String> shown = PaneEcho.withoutEcho(List.of("$ ls", "src  tools"), "ls");

        assertEquals(List.of("$ ", "src  tools"), shown);
    }

    /** A terminal breaks the echo wherever the pane ends, so it sits in no single row. */
    @Test
    void anEchoTheTerminalBrokeAcrossRowsIsStillTakenOut() {
        List<String> shown =
                PaneEcho.withoutEcho(List.of("$ printf lo", "ng-command", "ng-command-output"), "printf long-command");

        assertEquals(List.of("$ ", "", "ng-command-output"), shown);
    }

    /** Two commands typed in turn are two echoes, and both come off their prompt lines. */
    @Test
    void everyRecordedEchoIsTakenOut() {
        List<String> shown = PaneEcho.withoutEcho(
                List.of("$ cd /srv/app", "$ make", "stopped at 3"), List.of("cd /srv/app", "make"));

        assertEquals(List.of("$ ", "$ ", "stopped at 3"), shown);
    }

    /**
     * The defect this pins: a prompt that redraws itself on submit shows the typed line twice, and
     * taking out only the first copy left the second to answer the wait.
     */
    @Test
    void anEchoTheShellDrewTwiceIsTakenOutBothTimes() {
        List<String> shown = PaneEcho.withoutEcho(
                List.of("> sleep 1; echo ready", "❯ sleep 1; echo ready", ""), "sleep 1; echo ready");

        assertEquals(List.of("> ", "❯ ", ""), shown);
    }

    /** A right-hand prompt follows the typed line on the same row, after whitespace. */
    @Test
    void anEchoFollowedByARightHandPromptIsStillTakenOut() {
        List<String> shown =
                PaneEcho.withoutEcho(List.of("❯ sleep 5; echo done          12:04:31"), "sleep 5; echo done");

        assertEquals(List.of("❯           12:04:31"), shown);
    }

    /**
     * A wrapped prompt puts what the pane printed on the same row as the prompt that follows the
     * echo, so a row the echo touches can still carry real output. Dropping the row lost it, and the
     * wait timed out against text plainly on screen — found on CI against a background command whose
     * marker also appears in the line that started it.
     */
    @Test
    void aRowTheEchoSharesWithRealOutputKeepsThatOutput() {
        String echo = "(sleep 1; echo the-server-is-ready) &";
        List<String> lines =
                List.of("runner@host:~/work", "$ (sleep 1; echo the-server-is-ready) &", "$ the-server-is-ready");

        assertEquals(List.of("runner@host:~/work", "$ ", "$ the-server-is-ready"), PaneEcho.withoutEcho(lines, echo));
    }

    /** The same text sent twice is one echo. Taking it out twice would take the pane's answer with it. */
    @Test
    void aRepeatedSendDoesNotTakeOutTheOutputItProduced() {
        PaneEcho echo = new PaneEcho();
        PaneId pane = new PaneId("%1");
        echo.record(pane, "status");
        echo.record(pane, "status");

        assertEquals(List.of("status"), echo.recentFor(pane));
    }

    /** A pane typed into once and never waited on does not keep its record for the server's life. */
    @Test
    void aRecordNobodyWaitsOnIsDroppedOnceItHasAged() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        echo.record(new PaneId("%1"), "typed once");

        now[0] = java.time.Duration.ofSeconds(11).toNanos();
        echo.record(new PaneId("%2"), "typed later");

        assertEquals(1, echo.size(), "only the young record is held");
    }

    /** Nothing to take out is not a reason to rebuild the lines. */
    @Test
    void linesWithoutTheEchoAreHandedBackUntouched() {
        List<String> lines = List.of("ready", "listening on 8080");

        assertSame(lines, PaneEcho.withoutEcho(lines, "deploy"));
    }
}
