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
     * A long prompt pushes a short command's output past the pane's width, and tmux breaks the line
     * to fit. Nothing in what the pane printed put that break there, so a row-by-row search must not
     * be stopped by it. Found on CI, where one runner's hostname made the prompt long enough and a
     * short local prompt never did: the wait timed out against text plainly on screen.
     */
    @Test
    void outputTheTerminalWrappedForDisplayIsStillMatched(Server server) {
        String pane = server.panes().get(0).id().value();
        // Pin the width rather than inherit it: the break only happens when the
        // prompt and the output together outrun the pane, and a wide default
        // would hide exactly what this pins.
        server.cmd("resize-window", "-t", pane, "-x", "80", "-y", "24");
        // Longer than the pane is wide, so tmux has to break it wherever the
        // prompt happens to leave the cursor.
        String marker = "wrapped-" + "x".repeat(120) + "-marker";

        send(server, pane, "(sleep 1; echo " + marker + ") &");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of(marker), "timeout", 20));

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
    }

    @Test
    void textThatArrivesIsMatchedAndTheWaitEndsAtOnce(Server server) {
        String pane = server.panes().get(0).id().value();
        send(server, pane, "(sleep 1; echo the-server-is-ready) &");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("the-server-is-ready"), "timeout", 20));

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
        assertEquals("the-server-is-ready", waited.matched());
        assertTrue(String.valueOf(waited.matchedLine()).contains("the-server-is-ready"));
        assertTrue(waited.seconds() < 15, "it must return on the match, not at the deadline");
    }

    /**
     * Text already on screen when a cursorless wait starts must not be reported as a fresh
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
     * Text typed but never submitted sits on the
     * pending input line, not in anything the pane produced. The new entry check this fix adds must
     * not turn that into a false {@code PRESENT_AT_ENTRY} - it has to stay a plain {@code TIMED_OUT},
     * exactly as a cursorless wait already handled it before this fix.
     */
    @Test
    void unsubmittedTypedTextIsNeverPresentAtEntry(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "pending-input-marker";
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
     * {@code send_keys} then {@code wait_for_text} for the same marker must not match the
     * typed command line itself, which a shell echoes back and which therefore contains the marker
     * too. {@link io.github.libtmux.TypedText} is what makes the difference - go through the real MCP {@link Typing}
     * operation rather than a raw {@code send-keys}, or nothing records the echo to exclude.
     */
    @Test
    void sendThenWaitDoesNotMatchTheEchoedCommandLine(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "echo-marker-cold";

        typeAndSubmit(server, pane, "sleep 1; echo " + marker);

        assertMatchedTheOutputNotTheEcho(server, pane, marker);
    }

    /** As above, against a shell whose prompt has already settled rather than a freshly split one. */
    @Test
    void sendThenWaitDoesNotMatchTheEchoedCommandLineOnAWarmShellEither(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "echo-marker-warm";
        // Asserted, not assumed: a run that does not finish keeps the pane, and the typing below
        // would then be refused for owning rather than for anything this test is about.
        assertEquals(
                "SIGNALLED",
                RunningCommands.run(
                                TestCalls.on(server, "pane_id", pane.id().value(), "command", "true", "timeout", 15))
                        .outcome(),
                "the warm-up run never finished, so it still owns the pane");

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
        // Generous on purpose: what is pinned here is which line matched, not how soon. The
        // command sleeps a second before printing, and a five-second budget left barely four for a
        // loaded machine to get there - it lost one lane of the matrix that way.
        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane.id().value(), "patterns", List.of(marker), "timeout", 20));

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

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
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
     * process prints anything. Only {@link io.github.libtmux.TypedText} can tell that line apart from real output, and
     * only a call that goes through it gets recorded there.
     */
    private static void send(Server server, String pane, String command) {
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of(command), "literal", true));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("Enter"), "literal", false));
    }
}
