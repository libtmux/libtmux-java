package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * The failure that makes a scraping wait untrustworthy. A pane already saying "ready" from before
     * the call must not satisfy a wait for something that has not happened yet.
     */
    @Test
    void textAlreadyOnScreenDoesNotSatisfyTheWait(Server server) {
        String pane = server.panes().get(0).id().value();
        RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo already-ready", "timeout", 15));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("already-ready"), "timeout", 2));

        assertEquals("TIMED_OUT", waited.outcome(), "only output arriving after the call counts");
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

    /** Sent without waiting, which is what makes this the tool for output nobody here authored. */
    private static void send(Server server, String pane, String command) {
        server.run(List.of("send-keys", "-l", "-t", pane, command));
        server.run(List.of("send-keys", "-t", pane, "Enter"));
    }
}
