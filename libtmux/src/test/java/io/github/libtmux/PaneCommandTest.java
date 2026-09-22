package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * How a run's output is told from its plumbing.
 *
 * <p>The typed line carries both markers, so everything here turns on matching a marker as a whole
 * line and never as a substring of the echo that contains it.
 */
final class PaneCommandTest {

    private static final List<String> TMUX = List.of("/usr/bin/tmux", "-S", "/tmp/s");

    @Test
    void theOutputIsWhatLiesBetweenTheMarkersAndTheEchoIsNot() {
        PaneCommand run = PaneCommand.fresh();
        String typed = run.typed(TMUX, "printf 'one\\ntwo\\n'");
        List<String> screen = List.of("$ " + typed, start(run), "one", "two", end(run, 0), "$ ");

        PaneCommand.Framed framed = run.frame(screen);

        assertEquals(List.of("one", "two"), framed.lines());
        assertTrue(framed.exact());
        assertEquals(OptionalInt.of(0), framed.status());
    }

    /** The echo holds both markers as substrings; a rule matching by containment would stop there. */
    @Test
    void theEchoIsNeverMistakenForAMarker() {
        PaneCommand run = PaneCommand.fresh();
        String typed = run.typed(TMUX, "true");

        PaneCommand.Framed framed = run.frame(List.of("$ " + typed));

        assertEquals(List.of(), framed.lines(), "only plumbing on screen, so no output");
        assertFalse(framed.exact(), "and no start marker was printed");
        assertEquals(OptionalInt.empty(), framed.status());
    }

    /**
     * A start marker that never printed is not in the echo's place: the echo mentions it, and a rule
     * matching by containment would take the echo for the start and call the output exact.
     */
    @Test
    void anEchoThatMentionsTheStartDoesNotStandInForIt() {
        PaneCommand run = PaneCommand.fresh();
        String typed = run.typed(TMUX, "echo one");

        PaneCommand.Framed framed = run.frame(List.of("$ " + typed, "one", end(run, 0)));

        assertFalse(framed.exact(), "no start marker was printed, so nothing says where the output began");
    }

    @Test
    void aStatusIsReadOnlyFromAWellFormedMarkerEndingItsRow() {
        PaneCommand run = PaneCommand.fresh();
        String mark = end(run, 0).substring(0, end(run, 0).length() - 1);

        assertEquals(OptionalInt.of(7), run.status(mark + "7"));
        assertEquals(OptionalInt.of(255), run.status(mark + "255"));
        assertEquals(OptionalInt.empty(), run.status(mark + "256"), "no shell reports a status above 255");
        assertEquals(OptionalInt.empty(), run.status(mark + "07"), "and none pads it");
        assertEquals(OptionalInt.empty(), run.status(mark), "the echo ends the marker in \\\"$?\\\", not a number");
        assertEquals(OptionalInt.empty(), run.status(mark + "\"$?\""));
    }

    /**
     * A command whose last line carried no newline leaves that line on the marker's own row. Reading
     * the marker only where it began a row dropped the status of every such command — {@code printf
     * foo} among them — kept the raw marker in the output, and called a finished run inexact.
     */
    @Test
    void aMarkerIsReadAtTheEndOfTheRowItSharesAndWhatItSharesItWithIsOutput() {
        PaneCommand run = PaneCommand.fresh();

        PaneCommand.Framed framed = run.frame(List.of(start(run), "one", "two" + end(run, 0)));

        assertEquals(List.of("one", "two"), framed.lines());
        assertTrue(framed.exact());
        assertEquals(OptionalInt.of(0), framed.status());
    }

    /**
     * A terminal echoes the interrupt character onto the row the shell's trap then prints the marker
     * on. The status is the shell's and is read; the echo is the terminal's and is not output.
     */
    @Test
    void anInterruptEchoIsReadPastRatherThanReturned() {
        PaneCommand run = PaneCommand.fresh();

        PaneCommand.Framed framed = run.frame(List.of(start(run), "^C" + end(run, 130)));

        assertEquals(List.of(), framed.lines());
        assertEquals(OptionalInt.of(130), framed.status());
    }

    /**
     * Exit alone leaves a command someone stops with no way to report, and the wait with nothing to
     * end it — worst on dash, which does not run an exit trap on an interrupt at all.
     */
    @Test
    void theTrapIsArmedForInterruptAndTerminateAsWellAsExit() {
        String typed = PaneCommand.fresh().typed(TMUX, "true");

        assertTrue(typed.contains("' 0 2 15; "), typed);
        assertTrue(typed.contains("\\trap - 0 2 15; "), typed);
    }

    /**
     * Asking tmux to rejoin wrapped rows also asks it to keep trailing spaces, and it pads the rows
     * out to the pane to do so on 3.2a where 3.7c does not. The same command answered {@code built}
     * on one and {@code built} followed by fifteen spaces on the other.
     */
    @Test
    void tmuxsOwnPaddingIsNotTheCommandsOutput() {
        PaneCommand run = PaneCommand.fresh();

        PaneCommand.Framed framed =
                run.frame(List.of(start(run), "built               ", "  indented    ", end(run, 0)));

        assertEquals(
                List.of("built", "  indented"),
                framed.lines(),
                "trailing padding goes, leading indentation is the command's and stays");
    }

    /** Output that outgrew the history loses its start marker; what remains is said to be inexact. */
    @Test
    void aFrameWithItsStartScrolledAwayIsInexact() {
        PaneCommand run = PaneCommand.fresh();

        PaneCommand.Framed framed = run.frame(List.of("line 9998", "line 9999", end(run, 1), "$ "));

        // "$" and not "$ ": every returned row loses its trailing space, here a prompt's, because
        // nothing can tell tmux's padding from a printed one.
        assertEquals(List.of("line 9998", "line 9999", "$"), framed.lines());
        assertFalse(framed.exact());
        assertEquals(OptionalInt.of(1), framed.status());
    }

    /** A word a shell reads as exactly itself, whatever it holds. */
    @Test
    void aQuotedWordSurvivesQuotesDollarsAndNewlines() {
        assertEquals("'it'\\''s $HOME'", PaneCommand.quote("it's $HOME"));
        assertEquals("'a\nb'", PaneCommand.quote("a\nb"));
    }

    /** Every byte, including a quote, comes back as itself. */
    @Test
    void quotedWordsRoundTripEveryByte() {
        for (int value = 0; value < 256; value++) {
            String word = "a" + (char) value + "b";
            assertEquals(word, readQuoted(PaneCommand.quote(word)), "byte " + value);
        }
        String mixed = "it's $HOME `date` \\\n;#{pane_id}";
        assertEquals(mixed, readQuoted(PaneCommand.quote(mixed)));
    }

    @Test
    void onlyAPosixShellIsTypedAt() {
        PaneCommand.requirePosixShell("bash");
        PaneCommand.requirePosixShell("/usr/bin/zsh");
        PaneCommand.requirePosixShell("-sh");
        assertThrows(IllegalStateException.class, () -> PaneCommand.requirePosixShell("vim"));
        assertThrows(IllegalStateException.class, () -> PaneCommand.requirePosixShell("fish"));
    }

    /** The POSIX form: quoted runs joined by {@code '\''}. */
    private static String readQuoted(String quoted) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < quoted.length()) {
            assertEquals('\'', quoted.charAt(index), quoted);
            index++;
            while (index < quoted.length() && quoted.charAt(index) != '\'') {
                text.append(quoted.charAt(index));
                index++;
            }
            assertTrue(index < quoted.length(), quoted);
            index++;
            if (index == quoted.length()) {
                break;
            }
            assertTrue(
                    index + 1 < quoted.length() && quoted.charAt(index) == '\\' && quoted.charAt(index + 1) == '\'',
                    quoted);
            text.append('\'');
            index += 2;
        }
        return text.toString();
    }

    private static String start(PaneCommand run) {
        String typed = run.typed(TMUX, "x");
        int at = typed.indexOf("'-p' '") + "'-p' '".length();
        return typed.substring(at, typed.indexOf('\'', at));
    }

    private static String end(PaneCommand run, int status) {
        return start(run).replaceAll("-s$", "-e") + ":" + status;
    }
}
