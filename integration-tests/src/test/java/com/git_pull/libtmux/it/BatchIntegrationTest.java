package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.batch.BatchResult;
import com.git_pull.libtmux.batch.OperationOutcome;
import com.git_pull.libtmux.batch.OperationResult;
import com.git_pull.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Batching, and the reason it cannot be done by counting replies.
 *
 * <p>tmux runs a group until a command fails and then discards the rest. An error in the first
 * position and an error in the last both come back as one nonzero exit, so the reply count
 * identifies the failure in only one position out of three. Each operation is attributed instead.
 */
@ExtendWith(TmuxExtension.class)
final class BatchIntegrationTest {

    private static List<OperationOutcome> outcomes(BatchResult result) {
        return result.operations().stream().map(OperationResult::outcome).toList();
    }

    @Test
    void everyOperationRunsAndIsReportedInOrder(Server server) {
        BatchResult result = server.batch()
                .add("new-window", "-d", "-n", "one")
                .add("new-window", "-d", "-n", "two")
                .add("display-message", "-p", "#{session_name}")
                .run();

        assertTrue(result.succeeded());
        assertEquals(
                List.of(OperationOutcome.COMPLETE, OperationOutcome.COMPLETE, OperationOutcome.COMPLETE),
                outcomes(result));
        assertEquals(List.of("libtmux"), result.operations().get(2).stdout(), "output is attributed to its own op");
        assertEquals(3, server.windows().size());
    }

    @Test
    void aFailureInTheFirstPositionSkipsEverythingAfterIt(Server server) {
        BatchResult result = server.batch()
                .add("select-pane", "-t", "=missing")
                .add("new-window", "-d", "-n", "never")
                .add("new-window", "-d", "-n", "also-never")
                .run();

        assertFalse(result.succeeded());
        assertEquals(
                List.of(OperationOutcome.FAILED, OperationOutcome.SKIPPED, OperationOutcome.SKIPPED), outcomes(result));
        assertEquals(1, server.windows().size(), "tmux discarded the rest of the group");
    }

    /** The position a reply count gets wrong: one reply arrived, and it belongs to the first op. */
    @Test
    void aFailureInTheMiddleIsAttributedToTheMiddle(Server server) {
        BatchResult result = server.batch()
                .add("new-window", "-d", "-n", "made")
                .add("select-pane", "-t", "=missing")
                .add("new-window", "-d", "-n", "never")
                .run();

        assertEquals(
                List.of(OperationOutcome.COMPLETE, OperationOutcome.FAILED, OperationOutcome.SKIPPED),
                outcomes(result));
        assertEquals(
                List.of("made"),
                server.windows().stream()
                        .map(window -> window.name())
                        .filter(name -> name.equals("made"))
                        .toList(),
                "the operation before the failure really was applied");
    }

    @Test
    void aFailureInTheLastPositionLeavesTheEarlierOnesComplete(Server server) {
        BatchResult result = server.batch()
                .add("new-window", "-d", "-n", "first")
                .add("new-window", "-d", "-n", "second")
                .add("select-pane", "-t", "=missing")
                .run();

        assertEquals(
                List.of(OperationOutcome.COMPLETE, OperationOutcome.COMPLETE, OperationOutcome.FAILED),
                outcomes(result));
        assertEquals(3, server.windows().size());
    }

    @Test
    void theFailureCarriesTmuxsOwnMessage(Server server) {
        BatchResult result = server.batch()
                .add("new-window", "-d", "-n", "fine")
                .add("select-pane", "-t", "=missing")
                .run();

        OperationResult failure = result.failure().orElseThrow();
        assertEquals(OperationOutcome.FAILED, failure.outcome());
        assertTrue(
                failure.stderr().stream().anyMatch(line -> line.contains("missing")),
                "the caller needs tmux's reason: " + failure.stderr());
    }

    @Test
    void outputIsSeparatedPerOperationRatherThanConcatenated(Server server) {
        BatchResult result = server.batch()
                .add("display-message", "-p", "alpha")
                .add("display-message", "-p", "beta")
                .run();

        assertEquals(List.of("alpha"), result.operations().get(0).stdout());
        assertEquals(List.of("beta"), result.operations().get(1).stdout());
    }

    @Test
    void anEmptyBatchRunsNothing(Server server) {
        BatchResult result = server.batch().run();

        assertEquals(List.of(), result.operations());
        assertTrue(result.succeeded(), "nothing failed because nothing was asked");
    }

    @Test
    void aBatchIsOneInvocationNotSeveral(Server server) {
        int before = server.windows().size();

        server.batch()
                .add("new-window", "-d", "-n", "a")
                .add("new-window", "-d", "-n", "b")
                .add("new-window", "-d", "-n", "c")
                .run();

        assertEquals(before + 3, server.windows().size());
    }
}
