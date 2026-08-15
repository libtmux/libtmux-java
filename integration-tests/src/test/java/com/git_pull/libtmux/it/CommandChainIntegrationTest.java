package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.batch.BatchResult;
import com.git_pull.libtmux.batch.OperationOutcome;
import com.git_pull.libtmux.batch.OperationResult;
import com.git_pull.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Chaining, and the property it is built on.
 *
 * <p>tmux moves its own current target as a group runs, so a step can act on what the previous step
 * made without naming it. Without that, building a window and typing into its second pane costs a
 * round trip per step just to learn ids.
 */
@ExtendWith(TmuxExtension.class)
final class CommandChainIntegrationTest {

    @Test
    void eachStepActsOnWhatTheLastOneMade(Server server) throws Exception {
        BatchResult result = server.chain()
                .newWindow("chained")
                .splitLeftRight()
                .sendLine("echo chained-landed-here")
                .run();

        assertTrue(result.succeeded(), result.toString());
        Window built = server.windows().stream()
                .filter(window -> window.name().equals("chained"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, built.panes().size(), "the split applied to the window the chain had just made");

        List<Pane> panes = built.panes();
        assertTrue(
                await(() -> panes.get(1).capture().stream().anyMatch(line -> line.contains("chained-landed-here"))),
                "the keys went to the pane the split produced, not to the one the chain started from");
    }

    @Test
    void theWholeChainIsOneInvocation(Server server) {
        BatchResult result = server.chain()
                .newWindow("one")
                .newWindow("two")
                .newWindow("three")
                .run();

        assertEquals(3, result.operations().size());
        assertEquals(4, server.windows().size(), "the fixture window plus three");
    }

    @Test
    void aChainThatFailsSaysWhichStepAndWhichNeverRan(Server server) {
        BatchResult result = server.chain()
                .newWindow("made")
                .then("select-pane", "-t", "=missing")
                .newWindow("never")
                .run();

        assertEquals(
                List.of(OperationOutcome.COMPLETE, OperationOutcome.FAILED, OperationOutcome.SKIPPED),
                result.operations().stream().map(OperationResult::outcome).toList());
        assertTrue(
                server.windows().stream().noneMatch(window -> window.name().equals("never")),
                "tmux discarded the rest of the group");
    }

    /**
     * A layout name tmux does not recognise ends the whole server on 3.3a, taking every session on
     * the socket with it. The chain refuses it before anything is dispatched.
     */
    @Test
    void anUnknownLayoutIsRefusedBeforeAnythingRuns(Server server) {
        assertThrows(
                IllegalArgumentException.class,
                () -> server.chain().newWindow("safe").arrange("not-a-real-layout"),
                "the check has to happen while building the chain, not when running it");

        assertEquals(1, server.windows().size(), "and nothing was dispatched");
    }

    @Test
    void aRecognisedLayoutIsApplied(Server server) {
        BatchResult result = server.chain()
                .newWindow("arranged")
                .splitLeftRight()
                .arrange("even-horizontal")
                .run();

        assertTrue(result.succeeded(), result.toString());
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
