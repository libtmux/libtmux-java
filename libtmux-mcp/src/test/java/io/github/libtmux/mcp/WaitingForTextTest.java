package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

        WaitingForText.Waited waited =
                afterEntry(server, pane, "echo " + marker, "patterns", List.of(marker), "timeout", 20);

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
    }

    @Test
    void textThatArrivesIsMatchedAndTheWaitEndsAtOnce(Server server) {
        String pane = server.panes().get(0).id().value();
        WaitingForText.Waited waited = afterEntry(
                server, pane, "echo the-server-is-ready", "patterns", List.of("the-server-is-ready"), "timeout", 20);

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
        assertEquals("the-server-is-ready", waited.matched());
        assertTrue(String.valueOf(waited.matchedLine()).contains("the-server-is-ready"));
        assertTrue(waited.seconds() < 15, "it must return on the match, not at the deadline");
    }

    /**
     * Text already on screen when a cursorless wait starts is reported as {@code PRESENT_AT_ENTRY}
     * with that text included - not a fresh {@code MATCHED}, and not a {@code TIMED_OUT} that hides it.
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
     * Text typed but never submitted sits on the pending input line, not in anything the pane
     * produced, so a cursorless wait for it stays a plain {@code TIMED_OUT}.
     */
    @Test
    void unsubmittedTypedTextIsNeverPresentAtEntry(Server server) {
        Pane pane = server.panes().get(0);
        String marker = "pending-input-marker";
        // A shell still starting up prints its first prompt straight after these unread bytes,
        // with nothing between them, so the echo check cannot discount them.
        assertEquals(
                "SIGNALLED",
                RunningCommands.run(
                                TestCalls.on(server, "pane_id", pane.id().value(), "command", "true", "timeout", 15))
                        .outcome(),
                "the warm-up run never finished, so it still owns the pane");
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

        assertMatchedTheOutputNotTheEcho(
                afterEntry(server, pane.id().value(), "echo " + marker, "patterns", List.of(marker), "timeout", 20),
                marker);
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

        assertMatchedTheOutputNotTheEcho(
                afterEntry(server, pane.id().value(), "echo " + marker, "patterns", List.of(marker), "timeout", 20),
                marker);
    }

    private static void typeAndSubmit(Server server, Pane pane, String line) {
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane.id().value(), "keys", List.of(line), "literal", true));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane.id().value(), "keys", List.of("Enter"), "literal", false));
    }

    private static void assertMatchedTheOutputNotTheEcho(WaitingForText.Waited waited, String marker) {
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
        WaitingForText.Waited waited = afterEntry(
                server,
                pane,
                "echo 'error: it did not build'",
                "patterns",
                List.of("listening on"),
                "stop",
                List.of("error:"),
                "timeout",
                25);

        assertEquals("STOPPED", waited.outcome());
        assertEquals("error:", waited.matched());
        assertTrue(waited.seconds() < 20, "the stop pattern is what saves the rest of the budget");
        assertTrue(String.valueOf(waited.note()).contains("failure"), String.valueOf(waited.note()));
    }

    @Test
    void withNoPatternsAnyNewOutputEndsTheWait(Server server) {
        String pane = server.panes().get(0).id().value();
        WaitingForText.Waited waited = afterEntry(server, pane, "echo anything-at-all", "timeout", 20);

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
        WaitingForText.Waited waited =
                afterEntry(server, pane, "echo '[FAILED] one test'", "patterns", List.of("[FAILED]"), "timeout", 20);

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
        WaitingForText.Waited waited =
                afterEntry(server, pane, "echo lone-pattern-seen", "patterns", "lone-pattern-seen", "timeout", 20);

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

    /** Output is released only after the native entry capture has finished. */
    private WaitingForText.Waited afterEntry(Server server, String pane, String command, Object... arguments) {
        AtomicBoolean waiting = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport gated = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    CommandResult result = processes.execute(request);
                    boolean capture = request.commands().stream()
                            .flatMap(List::stream)
                            .anyMatch(
                                    argument -> argument.equals("capture-pane") || argument.contains("'capture-pane'"));
                    if (waiting.get() && capture && result.succeeded() && released.compareAndSet(false, true)) {
                        assertTrue(server.cmd("send-keys", "-t", pane, "Enter").succeeded());
                    }
                    return result;
                }

                @Override
                public void close() {}
            };
            try (Server measured = Server.using(server.config(), gated)) {
                Pane target = Targets.pane(measured, pane);
                typeAndSubmit(measured, target, "read libtmux_gate; " + command);
                Object[] parameters = new Object[arguments.length + 2];
                parameters[0] = "pane_id";
                parameters[1] = pane;
                System.arraycopy(arguments, 0, parameters, 2, arguments.length);
                waiting.set(true);
                WaitingForText.Waited waited = WaitingForText.waitFor(TestCalls.on(measured, parameters));
                assertTrue(released.get(), "the wait did not take its entry capture");
                return waited;
            }
        }
    }
}
