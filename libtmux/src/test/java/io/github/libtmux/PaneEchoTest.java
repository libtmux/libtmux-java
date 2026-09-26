package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordLiteral(identity, pane, "status\n").confirm();
        echo.recordLiteral(identity, pane, "status\n").confirm();

        assertEquals(List.of("status"), echo.liveFor(identity, pane).recent());
    }

    /** A pane typed into once and never waited on does not keep its record for the server's life. */
    @Test
    void aRecordNobodyWaitsOnIsDroppedOnceItHasAged() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        ServerIdentity identity = testIdentity();
        echo.recordLiteral(identity, new PaneId("%1"), "typed once\n").confirm();

        now[0] = Duration.ofSeconds(11).toNanos();
        echo.recordLiteral(identity, new PaneId("%2"), "typed later\n").confirm();

        assertEquals(1, echo.size(), "only the young record is held");
    }

    /** Nothing to take out is not a reason to rebuild the lines. */
    @Test
    void linesWithoutTheEchoAreHandedBackUntouched() {
        List<String> lines = List.of("ready", "listening on 8080");

        assertSame(lines, PaneEcho.withoutEcho(lines, "deploy"));
    }

    @Test
    void submitFinalizesThePendingLineIntoRecent() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("echo MARKER")).confirm();
        assertEquals(List.of("echo MARKER"), echo.liveFor(identity, pane).pending());

        echo.recordKeys(identity, pane, List.of("Enter")).confirm();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.pending());
        assertEquals(List.of("echo MARKER"), live.recent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"C-u", "C-c"})
    void killLineDiscardsAndDiscountsThePendingLine(String killKey) {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("half-typed")).confirm();

        echo.recordKeys(identity, pane, List.of(killKey)).confirm();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.pending());
        assertEquals(List.of("half-typed"), live.recent(), "a shell can redraw the killed line before clearing it");
    }

    @Test
    void eraseBanksThePreEditValueOnceThenShrinksWithoutUnderflow() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("xMARKER")).confirm();

        echo.recordKeys(
                        identity,
                        pane,
                        List.of(
                                "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace", "BSpace",
                                "BSpace"))
                .confirm();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.pending());
        assertEquals(List.of("xMARKER"), live.recent(), "the pre-edit value is banked exactly once");
    }

    @Test
    void forwardDeleteIsANoOp() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("stayput")).confirm();

        echo.recordKeys(identity, pane, List.of("DC")).confirm();

        assertEquals(List.of("stayput"), echo.liveFor(identity, pane).pending());
    }

    @Test
    void anUnmodelledKeyClearsThePendingLineButNotRecent() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("finished")).confirm();
        echo.recordKeys(identity, pane, List.of("Enter")).confirm();
        echo.recordKeys(identity, pane, List.of("stale-partial")).confirm();

        echo.recordKeys(identity, pane, List.of("Left")).confirm();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.pending(), "an unmodelled key fails open rather than keeping stale text");
        assertEquals(List.of("finished"), live.recent(), "an already-finished line is untouched");
    }

    @Test
    void aControlComboThisClassifierDoesNotModelAlsoFailsOpen() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("stale-partial")).confirm();

        echo.recordKeys(identity, pane, List.of("C-a")).confirm();

        assertEquals(List.of(), echo.liveFor(identity, pane).pending());
    }

    @Test
    void multiLineLiteralWriteSplitsIntoFinishedLinesAndAPendingRemainder() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");

        echo.recordLiteral(identity, pane, "first\nsecond\nthird").confirm();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of("first", "second"), live.recent());
        assertEquals(List.of("third"), live.pending());
    }

    @Test
    void aTrailingLineBreakSubmitsEveryLine() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");

        echo.recordLiteral(identity, pane, "first\nsecond\n").confirm();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of("first", "second"), live.recent());
        assertEquals(List.of(), live.pending());
    }

    @Test
    void aSubmitStaysDiscountedAsPendingUntilConfirmed() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("echo MARKER")).confirm();

        PaneEcho.Recorded recorded = echo.recordKeys(identity, pane, List.of("Enter"));

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.recent(), "not safe to union into recent until the dispatch is confirmed");
        assertEquals(List.of("echo MARKER"), live.pending(), "still fully discounted, just not yet filed away");

        recorded.confirm();

        PaneEcho.Live confirmed = echo.liveFor(identity, pane);
        assertEquals(List.of(), confirmed.pending());
        assertEquals(List.of("echo MARKER"), confirmed.recent());
    }

    @Test
    void rollingBackASubmitLeavesNothingInRecentAndRestoresThePendingLine() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("echo MARKER")).confirm();

        PaneEcho.Recorded recorded = echo.recordKeys(identity, pane, List.of("Enter"));
        recorded.rollback();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.recent(), "the Enter dispatch never happened; nothing was really submitted");
        assertEquals(List.of("echo MARKER"), live.pending(), "back to unsubmitted, exactly as before the attempt");
    }

    @Test
    void aMultiLineWriteStaysDiscountedAsPendingUntilConfirmed() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");

        PaneEcho.Recorded recorded = echo.recordLiteral(identity, pane, "first\nsecond\nthird");

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.recent());
        assertEquals(List.of("first", "second", "third"), live.pending());

        recorded.confirm();

        PaneEcho.Live confirmed = echo.liveFor(identity, pane);
        assertEquals(List.of("first", "second"), confirmed.recent());
        assertEquals(List.of("third"), confirmed.pending());
    }

    @Test
    void anEraseBanksThePreEditValueAsPendingUntilConfirmed() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("xMARKER")).confirm();

        PaneEcho.Recorded recorded = echo.recordKeys(identity, pane, List.of("BSpace"));

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.recent());
        assertEquals(List.of("xMARKER", "xMARKE"), live.pending(), "both the pre-edit and the shrunk value stay live");

        recorded.confirm();

        PaneEcho.Live confirmed = echo.liveFor(identity, pane);
        assertEquals(List.of("xMARKER"), confirmed.recent());
        assertEquals(List.of("xMARKE"), confirmed.pending());
    }

    @Test
    void pendingHasNoAgeLimitButRecentDoes() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordLiteral(identity, pane, "finished\n").confirm();
        echo.recordKeys(identity, pane, List.of("still-typing")).confirm();

        now[0] = Duration.ofSeconds(11).toNanos();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.recent(), "the finished line aged out");
        assertEquals(List.of("still-typing"), live.pending(), "an unsubmitted line has no TTL");
    }

    @Test
    void aPendingLineLongerThanTheBoundFailsOpenRatherThanGrowingForever() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");

        echo.recordKeys(identity, pane, List.of("x".repeat(5_000))).confirm();

        assertEquals(
                List.of(),
                echo.liveFor(identity, pane).pending(),
                "an unbounded line is dropped rather than tracked forever");
    }

    @Test
    void aPendingLineNeverSubmittedIsEventuallyReclaimed() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        ServerIdentity identity = testIdentity();
        echo.recordKeys(identity, new PaneId("%1"), List.of("never-submitted")).confirm();

        now[0] = Duration.ofHours(2).toNanos();
        echo.recordKeys(identity, new PaneId("%2"), List.of("triggers-the-sweep"))
                .confirm();

        assertEquals(1, echo.size(), "an abandoned pending line does not live for the life of the process");
    }

    @Test
    void aReusedPaneIdDoesNotInheritAnEarlierIncarnationsRecord() {
        PaneEcho echo = new PaneEcho();
        PaneId pane = new PaneId("%1");
        ServerIdentity before = ServerIdentity.of(
                        "test", ServerEndpoint.socketPath(Path.of("/tmp/pane-echo-before.sock")))
                .at(111, java.util.OptionalLong.of(1_790_000_000L));
        ServerIdentity after = ServerIdentity.of(
                        "test", ServerEndpoint.socketPath(Path.of("/tmp/pane-echo-before.sock")))
                .at(222, java.util.OptionalLong.of(1_790_000_000L));
        echo.recordKeys(before, pane, List.of("stale-from-before-restart")).confirm();

        assertEquals(List.of(), echo.liveFor(after, pane).pending());
    }

    @Test
    void rollbackUndoesTheMutationItWasGivenFor() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("kept")).confirm();
        echo.recordKeys(identity, pane, List.of("Enter")).confirm();

        PaneEcho.Recorded recorded = echo.recordKeys(identity, pane, List.of("never-happened"));
        recorded.rollback();

        PaneEcho.Live live = echo.liveFor(identity, pane);
        assertEquals(List.of(), live.pending());
        assertEquals(List.of("kept"), live.recent());
    }

    @Test
    void rollingBackAPanesFirstEverRecordRemovesItEntirely() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");

        PaneEcho.Recorded recorded = echo.recordKeys(identity, pane, List.of("never-happened"));
        recorded.rollback();

        assertEquals(0, echo.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"first\nsecond", "first\rsecond", "first\r\nsecond"})
    void fallbackTextAndLiteralTextRecognizeLineBreaks(String text) {
        PaneEcho literal = new PaneEcho();
        PaneEcho keys = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");

        literal.recordLiteral(identity, pane, text).confirm();
        keys.recordKeys(identity, pane, List.of(text)).confirm();

        assertEquals(List.of("first"), literal.liveFor(identity, pane).recent());
        assertEquals(List.of("second"), literal.liveFor(identity, pane).pending());
        assertEquals(literal.liveFor(identity, pane), keys.liveFor(identity, pane));
    }

    @Test
    void aShorterEchoDoesNotPreventMaskingALongerEcho() {
        assertEquals(
                List.of("$ ", "", "MARKER"),
                PaneEcho.withoutEcho(List.of("$ echo", "echo MARKER", "MARKER"), List.of("echo", "echo MARKER")));
    }

    @Test
    void anAbandonedRecordExpiresWhenReadWithoutAnotherWrite() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("abandoned")).confirm();

        now[0] = Duration.ofHours(2).toNanos();

        assertEquals(PaneEcho.Live.NONE, echo.liveFor(identity, pane));
        assertEquals(0, echo.size());
    }

    @Test
    void anOversizedSubmittedLineIsNotRetained() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordLiteral(identity, pane, "x".repeat(5_000) + "\n").confirm();

        assertEquals(List.of(), echo.liveFor(identity, pane).recent());
    }

    @Test
    void typingAfterAnUnknownKeyStaysUntrackedUntilSubmit() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("partial", "Left")).confirm();
        echo.recordKeys(identity, pane, List.of("more")).confirm();
        assertEquals(List.of(), echo.liveFor(identity, pane).pending());

        echo.recordKeys(identity, pane, List.of("Enter", "next")).confirm();
        assertEquals(List.of("next"), echo.liveFor(identity, pane).pending());
        assertEquals(List.of(), echo.liveFor(identity, pane).recent());
    }

    @Test
    void overlappingDispatchesKeepTheirSubmittedLines() throws Exception {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        CountDownLatch started = new CountDownLatch(1);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            PaneEcho.Recorded first = echo.recordLiteral(identity, pane, "first\n");
            var next = calls.submit(() -> {
                started.countDown();
                echo.recordLiteral(identity, pane, "second\n").confirm();
            });
            try {
                assertTrue(started.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
            } finally {
                first.confirm();
            }
            next.get(1, TimeUnit.SECONDS);
            assertEquals(
                    List.of("first", "second"), echo.liveFor(identity, pane).recent());
        }
    }

    @Test
    void submittedEchoAgeStartsWhenDispatchSettles() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        PaneEcho.Recorded recorded = echo.recordLiteral(identity, pane, "late-reply\n");
        now[0] = Duration.ofSeconds(11).toNanos();
        recorded.confirm();

        assertEquals(List.of("late-reply"), echo.liveFor(identity, pane).recent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\b", "\u007f"})
    void literalEraseBytesApplyTheSameEditsAsNamedKeys(String erase) {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordLiteral(identity, pane, "abcd").confirm();
        echo.recordLiteral(identity, pane, erase).confirm();

        assertEquals(List.of("abc"), echo.liveFor(identity, pane).pending());
        assertEquals(List.of("abcd"), echo.liveFor(identity, pane).recent());
    }

    @Test
    void anUnrecognizedFunctionKeyNameIsLiteralFallbackText() {
        PaneEcho echo = new PaneEcho();
        ServerIdentity identity = testIdentity();
        PaneId pane = new PaneId("%1");
        echo.recordKeys(identity, pane, List.of("F13")).confirm();

        assertEquals(List.of("F13"), echo.liveFor(identity, pane).pending());
    }

    private static ServerIdentity testIdentity() {
        return ServerIdentity.of("test", ServerEndpoint.socketPath(Path.of("/tmp/pane-echo-test.sock")));
    }
}
