package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Running a command and knowing how it ended, against real tmux.
 *
 * <p>The whole point of this tool is that a model does not have to guess. These cases are the ways
 * guessing goes wrong: a command that failed, one still running at the deadline, and output that
 * has to be told apart from the shell's echo of the plumbing that waits for it.
 */
@ExtendWith(TmuxExtension.class)
final class RunningCommandsTest {

    @Test
    void aCommandThatSucceedsComesBackWithItsOutputAndStatus(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo hello"));

        assertEquals("SIGNALLED", ran.outcome());
        assertEquals(0, ran.exitStatus());
        assertEquals(java.util.List.of("hello"), ran.output(), "only what the command printed");
        assertTrue(ran.framed(), "the plumbing was cut out exactly");
    }

    /**
     * The reason this tool exists rather than send-then-look: a failure is a number, not something to
     * infer from what the screen says.
     */
    @Test
    void aCommandThatFailsReportsItsStatus(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "exit 3"));

        assertEquals("SIGNALLED", ran.outcome());
        assertEquals(3, ran.exitStatus());
    }

    @Test
    void whatACommandPrintsOnStandardErrorIsKeptToo(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran = RunningCommands.run(
                TestCalls.on(server, "pane_id", pane, "command", "echo out; echo err 1>&2; exit 1"));

        assertEquals(1, ran.exitStatus());
        assertEquals(java.util.List.of("out", "err"), ran.output());
    }

    /**
     * The case that defeats matching the plumbing by its shape. In a narrow pane the shell's echo of
     * the payload wraps across several rows, and a row of it holds the same marker text the framing
     * looks for — so the frame is matched by whole-line equality, which an echo never satisfies.
     */
    @Test
    void aPaneTooNarrowToShowTheCommandStillYieldsOnlyItsOutput(Server server) {
        server.cmd("new-window", "-d", "-n", "narrow");
        server.cmd("resize-window", "-t", "narrow", "-x", "40", "-y", "20");
        String pane = server.panes().stream()
                .filter(candidate -> candidate.window().name().equals("narrow"))
                .findFirst()
                .orElseThrow()
                .id()
                .value();

        RunningCommands.Ran ran = RunningCommands.run(
                TestCalls.on(server, "pane_id", pane, "command", "printf 'alpha\\nbeta\\ngamma\\n'"));

        assertEquals(java.util.List.of("alpha", "beta", "gamma"), ran.output());
        assertTrue(ran.framed());
        assertTrue(
                ran.output().stream().noneMatch(line -> line.contains("wait-for")),
                "no part of the plumbing may reach the model: " + ran.output());
    }

    /**
     * A command still running at the deadline is not a failure to report as one. What it printed so
     * far is worth having, and the note has to say what to do next.
     */
    @Test
    void aCommandStillRunningAtTheDeadlineSaysSoAndHandsBackWhatItHas(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran = RunningCommands.run(
                TestCalls.on(server, "pane_id", pane, "command", "echo started; sleep 30", "timeout", 6));

        assertEquals("TIMED_OUT", ran.outcome());
        assertNull(ran.exitStatus(), "a command that has not finished has no status");
        assertTrue(ran.output().contains("started"), ran.output().toString());
        assertNotNull(ran.note());
        assertTrue(String.valueOf(ran.note()).contains("still running"), String.valueOf(ran.note()));
        assertTrue(ran.seconds() < 20, "it must return at its deadline, not at the command's end");
    }

    @Test
    void theTimeoutIsClampedToTheCeilingAndTheAnswerSaysWhatWasEnforced(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "true", "timeout", 9999));

        assertEquals(
                Waits.CEILING.toSeconds(), (long) ran.effectiveTimeout(), "an over-large ask is clamped, not refused");
    }

    /** Output has to be framed out of a pane that already had text in it from before the call. */
    @Test
    void outputIsSeparatedFromWhateverThePaneAlreadyShowed(Server server) {
        String pane = server.panes().get(0).id().value();
        RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo earlier-output"));

        RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo later"));

        assertEquals(java.util.List.of("later"), ran.output(), "the earlier run must not leak in");
    }

    @Test
    void aCommandPrintingMoreThanAskedForKeepsTheNewestAndSaysItDropped(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "seq 1 40", "max_lines", 5));

        assertEquals(5, ran.output().size());
        assertEquals("40", ran.output().get(4), "the newest line survives");
        assertTrue(ran.truncated());
        assertTrue(ran.linesDropped() >= 35, "it must say how much it dropped, not hide it");
    }

    /**
     * A command runs in a subshell, so what it changes about the shell does not outlive it. That is
     * what keeps {@code exit 3} from closing the pane, and it is the one way this differs from typing
     * the command by hand — pinned here because the tool's description promises it.
     */
    @Test
    void aCommandCannotChangeThePanesShellAndCannotEndIt(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "mine=set; cd /"));
        RunningCommands.Ran after =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo \"[$mine]\""));

        assertEquals(java.util.List.of("[]"), after.output(), "the assignment did not escape its subshell");
        assertEquals(1, server.panes().size(), "and exiting inside it did not take the pane with it");
    }

    /** A variable a person set in the pane themselves must survive the plumbing running around it. */
    @Test
    void thePlumbingDoesNotDisturbThePanesOwnShellVariables(Server server) {
        String pane = server.panes().get(0).id().value();
        server.run(java.util.List.of("send-keys", "-l", "-t", pane, "theirs=kept"));
        server.run(java.util.List.of("send-keys", "-t", pane, "Enter"));

        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo \"$theirs\""));

        assertEquals(java.util.List.of("kept"), ran.output());
    }

    @Test
    void aPaneThatIsNotThereSaysWhichToolFindsOne(Server server) {
        ObjectDoesNotExist refused = assertThrows(
                ObjectDoesNotExist.class,
                () -> RunningCommands.run(TestCalls.on(server, "pane_id", "%999", "command", "true")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("tmux_list_panes"), message);
    }

    @Test
    void aStatusOptionIsNotLeftBehindOnThePane(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "true"));

        assertFalse(
                server.panes().get(0).options().all().keySet().stream().anyMatch(name -> name.startsWith("@st_")),
                "the exit status is read and then cleared away");
    }
}
