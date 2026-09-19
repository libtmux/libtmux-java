package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.TypingTest.await;
import static io.github.libtmux.mcp.TypingTest.borrowing;
import static io.github.libtmux.mcp.TypingTest.hasCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.TextOutcome;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TmuxExtension.class)
final class EchoContractTest {

    @BeforeEach
    void shellReady(Server server) throws InterruptedException {
        Pane pane = server.panes().getFirst();
        pane.respawn("env", "PS1=echo-test> ", "ENV=/dev/null", "/bin/sh", "-i");
        TextOutcome prompt = pane.awaitText("echo-test> ", Duration.ofSeconds(1));
        assertTrue(prompt == TextOutcome.APPEARED || prompt == TextOutcome.PRESENT_AT_ENTRY);
    }

    @Test
    void shortAnswerNeverMasksLongerRealOutput(Server server) {
        String pane = server.panes().getFirst().id().value();
        send(server, pane, "(sleep 1; echo ready) &");
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("y"), "literal", false));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("ready"), "timeout", 20));

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
        assertTrue(String.valueOf(waited.matchedLine()).contains("ready"));
    }

    @Test
    void waitStartedAfterTheSendMatchesOutputNotTheEcho(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "s2b-marker";
        send(server, pane, "sleep 1; echo " + marker);

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of(marker), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        String matchedLine = String.valueOf(waited.matchedLine());
        assertTrue(matchedLine.contains(marker) && !matchedLine.contains("echo "), "matched the echo: " + matchedLine);
    }

    @Test
    void waitStartedBeforeTheSendStillMatchesOutputNotTheEcho(Server server) throws Exception {
        String pane = server.panes().getFirst().id().value();
        String marker = "s2a-marker";
        CountDownLatch waitHasReadTheScreen = new CountDownLatch(1);
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport observing = borrowing(request -> {
                var result = processes.execute(request);
                if (hasCommand(request, "capture-pane")) {
                    waitHasReadTheScreen.countDown();
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), observing);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<WaitingForText.Waited> future = calls.submit(() -> WaitingForText.waitFor(
                        TestCalls.on(measured, "pane_id", pane, "patterns", List.of(marker), "timeout", 20)));
                await(waitHasReadTheScreen);

                send(measured, pane, "sleep 1; echo " + marker);

                WaitingForText.Waited waited = future.get(25, TimeUnit.SECONDS);
                assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
                String matchedLine = String.valueOf(waited.matchedLine());
                assertTrue(
                        matchedLine.contains(marker) && !matchedLine.contains("echo "),
                        "matched the echo instead of the output: " + matchedLine);
            }
        }
    }

    @Test
    void unsubmittedTextTimesOutWithoutPresentingTheTypedLineAsOutput(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "s3-marker";
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("echo " + marker), "literal", true));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of(marker), "timeout", 1));

        assertEquals("TIMED_OUT", waited.outcome(), waited.toString());
        assertTrue(
                waited.output().stream().noneMatch(line -> line.contains(marker)),
                "the unsubmitted line leaked into the caller-visible output: " + waited.output());
    }

    @Test
    void nonLiteralSendKeysTextIsRecordedTheSameAsLiteralText(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "non-literal-marker";
        Typing.sendKeys(
                TestCalls.on(server, "pane_id", pane, "keys", List.of("sleep 1; echo " + marker), "literal", false));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("Enter"), "literal", false));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of(marker), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        String matchedLine = String.valueOf(waited.matchedLine());
        assertTrue(matchedLine.contains(marker) && !matchedLine.contains("echo "), "matched the echo: " + matchedLine);
    }

    @Test
    void editsAreAppliedBeforeTheEchoIsMasked(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "s4-MARKER";
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("x" + marker), "literal", false));
        Typing.sendKeys(TestCalls.on(
                server,
                "pane_id",
                pane,
                "keys",
                List.of(
                        "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace",
                        "BSpace", "BSpace"),
                "literal",
                false));
        send(server, pane, "sleep 1; echo " + marker);

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of(marker), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        String matchedLine = String.valueOf(waited.matchedLine());
        assertTrue(matchedLine.contains(marker) && !matchedLine.contains("echo "), "matched the echo: " + matchedLine);
        assertFalse(String.valueOf(waited.output()).contains("BSpace"), "a key name leaked into tracked text");
    }

    @Test
    void coldShellGuidanceIsActionable(Server server) {
        String pane = server.panes().getFirst().id().value();

        Typing.Sent sent =
                Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("marker"), "literal", true));

        String note = String.valueOf(sent.note());
        assertTrue(note.contains("cold shell"), "the note names the risk: " + note);
        assertTrue(note.contains("wait for the prompt"), "the note says what to do about it: " + note);
    }

    @Test
    void unmodelledKeyFailsOpenAndDoesNotHideRealOutput(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "edited-marker";
        send(server, pane, "(sleep 1; printf '\\nprinted %s\\n' " + marker + ") &");
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of(marker), "literal", true));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("Left"), "literal", false));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("printed " + marker), "timeout", 5));

        assertEquals("MATCHED", waited.outcome(), "an unmodelled key must not go on hiding real output");
    }

    @Test
    void multiLinePasteMasksEveryLineOfItsOwnEcho(Server server) {
        assumeTrue(server.version().atLeast(new TmuxVersion(3, 4, "")), "paste cleanup requires tmux 3.4");
        Pane pane = server.panes().getFirst();
        String marker = "multiline-paste-marker";

        Typing.pasteText(TestCalls.on(
                server,
                "pane_id",
                pane.id().value(),
                "text",
                "echo first-line\nsleep 1; echo " + marker,
                "enter",
                true));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane.id().value(), "patterns", List.of(marker), "timeout", 20));

        assertEquals("MATCHED", waited.outcome(), "output the wait saw: " + waited.output());
        String matchedLine = String.valueOf(waited.matchedLine());
        assertTrue(matchedLine.contains(marker) && !matchedLine.contains("echo "), "matched the echo: " + matchedLine);
    }

    @Test
    void resizeDuringTheWaitDoesNotBreakTheMask(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "resize-marker";
        server.cmd("resize-window", "-t", pane, "-x", "80", "-y", "24");
        send(server, pane, "sleep 1; echo " + marker);
        server.cmd("resize-window", "-t", pane, "-x", "40", "-y", "24");

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of(marker), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        String matchedLine = String.valueOf(waited.matchedLine());
        assertTrue(matchedLine.contains(marker) && !matchedLine.contains("echo "), "matched the echo: " + matchedLine);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWrappedUnsubmittedEchoDoesNotBecomeOutput(boolean resize, Server server) throws Exception {
        Pane target = server.panes().getFirst();
        String pane = target.id().value();
        server.cmd("resize-window", "-t", pane, "-x", "80", "-y", "24");
        Typing.sendKeys(TestCalls.on(
                server, "pane_id", pane, "keys", List.of("echo WRAP-MARKER-" + "x".repeat(140)), "literal", true));
        if (resize) {
            server.cmd("resize-window", "-t", pane, "-x", "40", "-y", "24");
        }

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("WRAP-MARKER"), "timeout", 0.1));

        assertEquals("TIMED_OUT", waited.outcome(), waited.toString());
        assertTrue(waited.output().stream().noneMatch(line -> line.contains("WRAP-MARKER")));
    }

    @Test
    void matchedLinePreservesRealOutputContainingDiscountedText(Server server) {
        String pane = server.panes().getFirst().id().value();
        send(server, pane, "(sleep 1; printf '\\n%s\\n' 'y ready') &");
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("y"), "literal", true));

        WaitingForText.Waited waited = WaitingForText.waitFor(
                TestCalls.on(server, "pane_id", pane, "patterns", List.of("ready"), "timeout", 20));

        assertEquals("MATCHED", waited.outcome());
        assertEquals("y ready", waited.matchedLine());
    }

    private static void send(Server server, String pane, String command) {
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of(command), "literal", true));
        Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("Enter"), "literal", false));
    }
}
