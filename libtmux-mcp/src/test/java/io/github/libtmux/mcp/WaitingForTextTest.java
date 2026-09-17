package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Waiting for output nobody here started.
 *
 * <p>The heuristic wait, and the one with the most ways to be quietly wrong: satisfied by text that
 * was already on screen, or held to the deadline by a run that failed in the first second. Both are
 * pinned here.
 */
@ExtendWith(TmuxExtension.class)
final class WaitingForTextTest {

    /**
     * A terminal wraps a line too long for the pane across rows; no single captured row then
     * carries the whole echo even though it is exactly what is on screen. Reproduced deterministically
     * here rather than by hand - it depends on the pane's width relative to the shell's own prompt
     * length, which a CI runner's longer default prompt hit and a short local one did not,
     * surfacing this once real tmux was involved (JAVA2-6 follow-up).
     */
    @Test
    void withoutEchoExcludesAnEchoTheTerminalWrappedAcrossRows() {
        String echo = "sleep 1; echo JAVA-D10-MARKER-WARM";
        List<String> wrapped = List.of("prompt $ true", "prompt $ sleep 1; echo JAVA-D10-M", "ARKER-WARM", "");

        List<String> filtered = WaitingForText.withoutEcho(wrapped, echo);

        assertEquals(List.of("prompt $ true", "prompt $ ", ""), filtered);
    }

    /** The ordinary case, a single row, still works exactly as before. */
    @Test
    void withoutEchoStillExcludesAnEchoThatFitsOnOneRow() {
        String echo = "echo unwrapped-marker";
        List<String> lines = List.of("prompt $ true", "prompt $ echo unwrapped-marker", "unwrapped-marker");

        List<String> filtered = WaitingForText.withoutEcho(lines, echo);

        assertEquals(List.of("prompt $ true", "prompt $ ", "unwrapped-marker"), filtered);
    }

    /**
     * A wrapped prompt puts what the pane printed on the same row as the prompt that follows the
     * echo, so a row the echo touches can still carry real output. Dropping the row would lose it
     * and the wait would time out against text plainly on screen — which is what a whole-row
     * exclusion did to a background command whose marker also appears in the line that started it.
     */
    @Test
    void withoutEchoKeepsOutputSharingARowWithTheEcho() {
        String echo = "(sleep 1; echo the-server-is-ready) &";
        List<String> lines =
                List.of("runner@host:~/work", "$ (sleep 1; echo the-server-is-ready) &", "$ the-server-is-ready");

        List<String> filtered = WaitingForText.withoutEcho(lines, echo);

        assertEquals(List.of("runner@host:~/work", "$ ", "$ the-server-is-ready"), filtered);
    }

    @Test
    void textThatArrivesIsMatchedAndTheWaitEndsAtOnce(Server server) {
        String pane = server.panes().get(0).id().value();
        send(server, pane, "(sleep 1; echo the-server-is-ready) &");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("the-server-is-ready"), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        assertEquals("the-server-is-ready", waited.matched());
        assertTrue(String.valueOf(waited.matchedLine()).contains("the-server-is-ready"));
        assertTrue(waited.seconds() < 15, "it must return on the match, not at the deadline");
    }

    /**
     * JAVA2-6: text already on screen when a cursorless wait starts must not be reported as a fresh
     * {@code MATCHED} - a pane already saying "ready" from before the call did not just become ready
     * - but it must not be silently invisible either. Before this fix it was: {@code TIMED_OUT} with
     * completely empty {@code output}, indistinguishable from the pattern never having appeared at
     * all, even though {@code capture-pane} showed it in plain sight.
     */
    @Test
    void textAlreadyOnScreenIsReportedAsPresentAtEntryNotAFreshMatch(Server server) {
        String pane = server.panes().get(0).id().value();
        RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo already-ready", "timeout", 15));
        // The wait has to start from a screen that already says it, or this is not that case.
        assertTrue(onScreen(server, pane, "already-ready"), "the output never reached the screen");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("already-ready"), "timeout", 2));

        assertEquals("PRESENT_AT_ENTRY", waited.outcome(), "not a fresh match, and not an invisible one either");
        assertEquals("already-ready", waited.matched());
        assertTrue(
                waited.output().stream().anyMatch(line -> line.contains("already-ready")),
                "the text really is there; hiding it is the bug this fixes: " + waited.output());
        assertTrue(waited.seconds() < 1, "answered from the entry screen, no watching needed");
    }

    /**
     * D1 point 2 (M2 in the round's own findings): text typed but never submitted sits on the
     * pending input line, not in anything the pane produced. The new entry check this fix adds must
     * not turn that into a false {@code PRESENT_AT_ENTRY} - it has to stay a plain {@code TIMED_OUT},
     * exactly as a cursorless wait already handled it before this fix.
     */
    @Test
    void unsubmittedTypedTextIsNeverPresentAtEntry(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "JAVA-D1-PENDING-MARKER";
        Typing.sendKeys(
                TestCalls.on(server, "pane_id", pane.id().value(), "keys", List.of("echo " + marker), "literal", true));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane.id().value(), "patterns", List.of(marker), "timeout", 1));

        assertEquals("TIMED_OUT", waited.outcome(), "the command was never run; there is nothing to report yet");
        assertTrue(
                waited.output().stream().noneMatch(line -> line.contains(marker)),
                "the pending, unsubmitted line must not leak into the caller-visible output: " + waited.output());
    }

    /**
     * D10: {@code send_keys} then {@code wait_for_text} for the same marker must not match the
     * typed command line itself, which a shell echoes back and which therefore contains the marker
     * too. {@link TypedEcho} is what makes the difference - go through the real MCP {@link Typing}
     * operation rather than a raw {@code send-keys}, or nothing records the echo to exclude.
     */
    @Test
    void sendThenWaitDoesNotMatchTheEchoedCommandLine(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "JAVA-D10-MARKER-COLD";

        typeAndSubmit(server, pane, "sleep 1; echo " + marker);

        assertMatchedTheOutputNotTheEcho(server, pane, marker);
    }

    /** As above, against a shell whose prompt has already settled rather than a freshly split one. */
    @Test
    void sendThenWaitDoesNotMatchTheEchoedCommandLineOnAWarmShellEither(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "JAVA-D10-MARKER-WARM";
        RunningCommands.run(TestCalls.on(server, "pane_id", pane.id().value(), "command", "true", "timeout", 15));

        typeAndSubmit(server, pane, "sleep 1; echo " + marker);

        assertMatchedTheOutputNotTheEcho(server, pane, marker);
    }

    /**
     * Types a line and submits it. The line sleeps before printing so its output lands after the
     * wait takes its starting cursor; output already on screen by then does not count, and a fast
     * shell would otherwise print it first.
     */
    private static void typeAndSubmit(Server server, Pane pane, String line) {
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane.id().value(), "keys", List.of(line), "literal", true));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane.id().value(), "keys", List.of("Enter"), "literal", false));
    }

    private static void assertMatchedTheOutputNotTheEcho(Server server, Pane pane, String marker) {
        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane.id().value(), "patterns", List.of(marker), "timeout", 5));

        assertEquals("MATCHED", waited.outcome(), "the output must eventually appear");
        String matchedLine = String.valueOf(waited.matchedLine());
        assertTrue(
                matchedLine.contains(marker) && !matchedLine.contains("echo "),
                "matched the typed command line, not its output: " + matchedLine);
        assertTrue(
                waited.output().stream().noneMatch(line -> line.contains("echo " + marker)),
                "the echoed command line leaked into the caller-visible output: " + waited.output());
    }

    /** Without a stop pattern the same run is waited on until the deadline; with one it comes back. */
    @Test
    void aStopPatternEndsTheWaitOnFailureRatherThanRunningToTheDeadline(Server server) {
        String pane = server.panes().get(0).id().value();
        send(server, pane, "(sleep 1; echo 'error: it did not build') &");

        WaitingForText.Waited waited = WaitingForText.waitFor(TestCalls.on(
                server,
                "pane_id",
                pane,
                "patterns",
                List.of("listening on"),
                "stop",
                List.of("error:"),
                "timeout",
                25));

        assertEquals("STOPPED", waited.outcome());
        assertEquals("error:", waited.matched());
        assertTrue(waited.seconds() < 20, "the stop pattern is what saves the rest of the budget");
        assertTrue(String.valueOf(waited.note()).contains("failure"), String.valueOf(waited.note()));
    }

    @Test
    void withNoPatternsAnyNewOutputEndsTheWait(Server server) {
        String pane = server.panes().get(0).id().value();
        send(server, pane, "(sleep 1; echo anything-at-all) &");

        WaitingForText.Waited waited = WaitingForText.waitFor(TestCalls.on(server, "pane_id", pane, "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        assertTrue(String.valueOf(waited.note()).contains("any new output"), String.valueOf(waited.note()));
    }

    @Test
    void aWaitThatFindsNothingSaysHowToCarryOnWithoutRereading(Server server) {
        String pane = server.panes().get(0).id().value();

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("never-appears"), "timeout", 2));

        assertEquals("TIMED_OUT", waited.outcome());
        assertNotNull(waited.cursor());
        assertTrue(String.valueOf(waited.note()).contains("cursor"), String.valueOf(waited.note()));
        assertTrue(String.valueOf(waited.note()).contains("stop"), "and to pass a failure marker next time");
    }

    /** A model waiting for "[FAILED]" means those characters, not a one-letter character class. */
    @Test
    void aPatternIsPlainTextUnlessAskedToBeAnExpression(Server server) {
        String pane = server.panes().get(0).id().value();
        send(server, pane, "(sleep 1; echo '[FAILED] one test') &");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("[FAILED]"), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
    }

    @Test
    void anExpressionThatWillNotCompileSaysSoRatherThanNeverMatching(Server server) {
        String pane = server.panes().get(0).id().value();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> WaitingForText.waitFor(TestCalls.on(
                        server, "pane_id", pane, "patterns", List.of("[unclosed"), "regex", true, "timeout", 2)));

        assertTrue(String.valueOf(refused.getMessage()).contains("Omit 'regex'"), refused.getMessage());
    }

    @Test
    void aCursorFromAnotherPaneIsRejectedBeforeWaiting(Server server) {
        String first = server.panes().get(0).id().value();
        String second = server.sessions().get(0).windows().get(0).split().id().value();
        String cursor = Reading.since(TestCalls.on(server, "pane_id", first)).cursor();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> WaitingForText.waitFor(
                        TestCalls.on(server, "pane_id", second, "cursor", cursor, "timeout", 0.2)));

        assertTrue(String.valueOf(refused.getMessage()).contains(first), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains(second), refused.getMessage());
    }

    /** A model that sends one string where the schema says a list means the one string. */
    @Test
    void aSinglePatternSentWithoutAListIsStillUnderstood(Server server) {
        String pane = server.panes().get(0).id().value();
        send(server, pane, "(sleep 1; echo lone-pattern-seen) &");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", "lone-pattern-seen", "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
    }

    @Test
    void theTimeoutIsClampedToTheCeiling(Server server) {
        String pane = server.panes().get(0).id().value();

        WaitingForText.Waited waited = WaitingForText.waitFor(TestCalls.on(
                server, "pane_id", pane, "patterns", List.of("no-pane-anywhere-prints-this"), "timeout", 0.2));

        assertEquals("TIMED_OUT", waited.outcome());
        assertTrue(waited.effectiveTimeout() <= Waits.CEILING.toSeconds());
    }

    private static boolean onScreen(Server server, String pane, String text) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (String.join("\n", server.cmd("capture-pane", "-p", "-t", pane).stdout())
                    .contains(text)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Starts a command in the background and returns at once, without a tracked run to learn when
     * it finishes - {@code wait_for_text} is for exactly that case, output nobody here is watching
     * for through a command result.
     *
     * <p>Goes through {@link Typing#sendKeys}, the same as {@link #typeAndSubmit}, rather than a raw
     * {@code send-keys}: every command here is an {@code echo <marker>} whose own typed-and-submitted
     * text contains the marker too, on screen the instant it is submitted - before the backgrounded
     * process prints anything. Only {@link TypedEcho} can tell that line apart from real output, and
     * only a call that goes through it gets recorded there.
     */
    private static void send(Server server, String pane, String command) {
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of(command), "literal", true));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("Enter"), "literal", false));
    }
}
