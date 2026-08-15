package com.git_pull.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where one tmux command ends and the next begins, and what a control-mode line makes of that.
 *
 * <p>Every expectation here was measured against tmux rather than derived from this code. The
 * measurements are in {@code docs/spikes/21-command-group-boundaries.md}; {@code
 * ExecutionModeConformanceTest} is what keeps the carriers agreeing about them.
 */
final class ControlClientTest {

    @Test
    void aSemicolonEndingAnArgumentEndsTheCommand() {
        assertTrue(ControlClient.isCommandGroup(List.of("kill-window;", "list-windows")));
        assertTrue(ControlClient.isCommandGroup(List.of("list-windows", ";", "list-panes")));
    }

    /** tmux looks at the end of an argument, so a semicolon anywhere else is just a character. */
    @Test
    void aSemicolonAnywhereElseIsPartOfTheArgument() {
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", "semi;colon")));
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", ";leading")));
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", "plain")));
    }

    @Test
    void aBackslashKeepsTheSemicolonInsteadOfEndingTheCommand() {
        assertFalse(ControlClient.isCommandGroup(List.of("display-message", "-p", "trailing\\;")));
    }

    /**
     * The process carrier reaches tmux's argv parser and this one does not, so the backslash that
     * parser would consume is consumed here instead. Passing it on would deliver a different
     * argument than the other carrier did.
     */
    @Test
    void theEscapeGuardingATrailingSemicolonIsSpentRatherThanSent() {
        assertEquals(
                "'display-message' '-p' 'trailing;'",
                ControlClient.line(List.of("display-message", "-p", "trailing\\;")));
    }

    @Test
    void everyOtherArgumentReachesTmuxExactlyAsGiven() {
        assertEquals(
                "'display-message' '-p' 'semi;colon'",
                ControlClient.line(List.of("display-message", "-p", "semi;colon")));
        assertEquals(
                "'display-message' '-p' 'it'\\''s quoted'",
                ControlClient.line(List.of("display-message", "-p", "it's quoted")));
    }
}
