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
    void aStatusIsReadOnlyFromAWholeWellFormedMarker() {
        PaneCommand run = PaneCommand.fresh();
        String mark = end(run, 0).substring(0, end(run, 0).length() - 1);

        assertEquals(OptionalInt.of(7), run.status(mark + "7"));
        assertEquals(OptionalInt.of(255), run.status(mark + "255"));
        assertEquals(OptionalInt.empty(), run.status(mark + "256"), "no shell reports a status above 255");
        assertEquals(OptionalInt.empty(), run.status(mark + "07"), "and none pads it");
        assertEquals(OptionalInt.empty(), run.status(mark), "the echo ends the marker in \\\"$?\\\", not a number");
        assertEquals(OptionalInt.empty(), run.status(mark + "\"$?\""));
    }

    /** Output that outgrew the history loses its start marker; what remains is said to be inexact. */
    @Test
    void aFrameWithItsStartScrolledAwayIsInexact() {
        PaneCommand run = PaneCommand.fresh();

        PaneCommand.Framed framed = run.frame(List.of("line 9998", "line 9999", end(run, 1), "$ "));

        assertEquals(List.of("line 9998", "line 9999", "$ "), framed.lines());
        assertFalse(framed.exact());
        assertEquals(OptionalInt.of(1), framed.status());
    }

    /** A word a shell reads as exactly itself, whatever it holds. */
    @Test
    void aQuotedWordSurvivesQuotesDollarsAndNewlines() {
        assertEquals("'it'\\''s $HOME'", PaneCommand.quote("it's $HOME"));
        assertEquals("'a\nb'", PaneCommand.quote("a\nb"));
    }

    @Test
    void onlyAPosixShellIsTypedAt() {
        PaneCommand.requirePosixShell("bash");
        PaneCommand.requirePosixShell("/usr/bin/zsh");
        PaneCommand.requirePosixShell("-sh");
        assertThrows(IllegalStateException.class, () -> PaneCommand.requirePosixShell("vim"));
        assertThrows(IllegalStateException.class, () -> PaneCommand.requirePosixShell("fish"));
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
