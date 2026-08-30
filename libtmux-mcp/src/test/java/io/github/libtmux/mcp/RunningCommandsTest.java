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
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import io.github.libtmux.transport.TmuxTransportException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Running a command and knowing how it ended, against real tmux.
 *
 * <p>The whole point of this tool is that a model does not have to guess. These cases are the ways
 * guessing goes wrong: a command that failed, one still running at the deadline, and output that
 * has to be told apart from the shell's echo of the plumbing that waits for it.
 */
@ExtendWith(TmuxExtension.class)
final class RunningCommandsTest {

    /**
     * The signal is sent by the pane, so it is the pane's PATH that decides which tmux sends it. A
     * client from another release than this server is dropped without delivering it.
     */
    @Test
    void thePaneSignalsWithThisServersTmuxRatherThanItsOwn(Server server, @TempDir Path decoy) throws Exception {
        Path impostor = decoy.resolve("tmux");
        Files.writeString(impostor, "#!/bin/sh\nexit 1\n");
        impostor.toFile().setExecutable(true);
        server.cmd("set-environment", "-t", "libtmux", "PATH", decoy + ":" + System.getenv("PATH"));
        String pane = server.sessions()
                .get(0)
                .newWindow("decoyed")
                .panes()
                .get(0)
                .id()
                .value();

        RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo routed"));

        assertEquals("SIGNALLED", ran.outcome(), "a tmux on the pane's PATH answered instead of this server's");
        assertEquals(0, ran.exitStatus());
    }

    @Test
    void aCommandThatSucceedsComesBackWithItsOutputAndStatus(Server server) {
        String pane = server.panes().get(0).id().value();

        RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo hello"));

        assertEquals("SIGNALLED", ran.outcome());
        assertEquals(0, ran.exitStatus());
        assertEquals(java.util.List.of("hello"), ran.output(), "only what the command printed");
        assertTrue(ran.framed(), "the plumbing was cut out exactly");
    }

    @Test
    void aPaneNotRunningAPosixShellIsRefused(Server server) {
        server.cmd("new-window", "-d", "-n", "not-a-shell", "cat");
        String pane = server.panes().stream()
                .filter(candidate -> candidate.window().name().equals("not-a-shell"))
                .findFirst()
                .orElseThrow()
                .id()
                .value();

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> RunningCommands.run(
                        TestCalls.on(server, "pane_id", pane, "command", "echo must-not-be-typed", "timeout", 0.1)));

        assertTrue(String.valueOf(refused.getMessage()).contains("POSIX-compatible shell"), refused.getMessage());
        assertTrue(
                server.panes().stream()
                        .filter(candidate -> candidate.id().value().equals(pane))
                        .findFirst()
                        .orElseThrow()
                        .capture()
                        .stream()
                        .noneMatch(line -> line.contains("must-not-be-typed")),
                "the rejected payload must not reach the foreground program");
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

    @Test
    void exitStatusSurvivesWhenTheStartMarkerRolledOutOfHistory(Server server) {
        server.run(java.util.List.of("set-option", "-g", "history-limit", "10"));
        server.run(java.util.List.of("new-window", "-d", "-n", "shallow"));
        String pane = server.panes().stream()
                .filter(candidate -> candidate.window().name().equals("shallow"))
                .findFirst()
                .orElseThrow()
                .id()
                .value();

        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "seq 1 80; exit 7"));

        assertEquals("SIGNALLED", ran.outcome());
        assertEquals(7, ran.exitStatus());
        assertFalse(ran.framed(), "the old start marker must actually have rolled away");
        assertTrue(
                ran.output().stream().noneMatch(line -> line.matches(".*lt[0-9a-f]{32}-[se].*")),
                "no surviving marker may leak into output: " + ran.output());
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
    void aTimedOutCommandLeavesNoStatusWhenItEventuallyFinishes(Server server) throws Exception {
        String pane = server.panes().get(0).id().value();
        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "sleep 1", "timeout", 0.1));
        server.panes().get(0).sendLine("printf 'timeout-cleanup-%s\\n' finished");

        assertEquals("TIMED_OUT", ran.outcome());
        assertTrue(
                await(() -> server.panes().get(0).capture().stream()
                        .anyMatch(line -> line.contains("timeout-cleanup-finished"))),
                "the timed-out command never released the pane's shell");
        assertFalse(
                server.panes().get(0).options().all().keySet().stream().anyMatch(name -> name.startsWith("@st_")),
                "the eventual exit status was left on the pane");
    }

    @Test
    void uncertainCommandDeliveryStillRunsTheAcceptedCommand(Server server, @TempDir Path temporary) throws Exception {
        String pane = server.panes().get(0).id().value();
        Path accepted = temporary.resolve("accepted");
        Path entered = temporary.resolve("entered");
        String gate = "uncertain-delivery-" + System.nanoTime();
        var wait = new java.util.ArrayList<>(java.util.List.of(server.config().binaryPath()));
        wait.addAll(server.config().endpoint().flags());
        wait.addAll(java.util.List.of("wait-for", gate));
        server.panes()
                .get(0)
                .sendLine("printf entered > " + Shell.quote(entered.toString()) + "; " + Shell.quoteAll(wait));
        assertTrue(await(() -> Files.exists(entered)), "the pane never entered the delivery gate");
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport uncertain = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (request.commands().get(0).stream().anyMatch(argument -> argument.contains("ch_lt"))) {
                    throw new TmuxTransportException("simulated failure after delivery", DispatchOutcome.UNKNOWN, null);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), uncertain)) {
                try {
                    assertThrows(
                            TmuxTransportException.class,
                            () -> RunningCommands.run(TestCalls.on(
                                    measured,
                                    "pane_id",
                                    pane,
                                    "command",
                                    "printf ran > " + Shell.quote(accepted.toString()))));
                } finally {
                    server.channel(gate).signal();
                }
                server.panes().get(0).sendLine("printf 'uncertain-cleanup-%s\\n' finished");

                assertTrue(
                        await(() -> Files.exists(accepted)), "an accepted command was lost after ambiguous delivery");
                assertEquals("ran", Files.readString(accepted));
                assertTrue(
                        await(() -> server.panes().get(0).capture().stream()
                                .anyMatch(line -> line.contains("uncertain-cleanup-finished"))),
                        "an ambiguously delivered command left the pane's shell waiting for cleanup");
            }
        }
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

        RunningCommands.Ran exited =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "mine=set; cd /; exit 3"));
        RunningCommands.Ran commented = RunningCommands.run(
                TestCalls.on(server, "pane_id", pane, "command", "echo comment-safe # comment", "timeout", 1));
        RunningCommands.Ran parenthesis =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", ": ); exit 7; #", "timeout", 1));
        RunningCommands.Ran after =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo \"[$mine]\""));

        assertEquals(3, exited.exitStatus(), "exit reports from the isolated command");
        assertEquals(java.util.List.of("comment-safe"), commented.output(), "a comment cannot hide the framing");
        assertEquals("SIGNALLED", parenthesis.outcome(), "a closing parenthesis cannot escape the command");
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
    void concurrentRunsDoNotMergeTheirCommandLines(Server server) throws Exception {
        String pane = server.panes().get(0).id().value();
        CountDownLatch bothLinesSent = new CountDownLatch(2);
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport interleaving = borrowing(request -> {
                CommandResult result = processes.execute(request);
                String argv = String.join("\0", request.commands().get(0));
                if (argv.contains("send-keys") && argv.contains("ch_lt")) {
                    bothLinesSent.countDown();
                    await(bothLinesSent);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), interleaving);
                    var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = calls.submit(() -> RunningCommands.run(TestCalls.on(
                        measured, "pane_id", pane, "command", "printf 'first-run-marker\\n'", "timeout", 2)));
                var second = calls.submit(() -> RunningCommands.run(TestCalls.on(
                        measured, "pane_id", pane, "command", "printf 'second-run-marker\\n'", "timeout", 2)));

                assertEquals(
                        java.util.List.of("first-run-marker"),
                        first.get(10, TimeUnit.SECONDS).output());
                assertEquals(
                        java.util.List.of("second-run-marker"),
                        second.get(10, TimeUnit.SECONDS).output());
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out arranging concurrent command delivery");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while arranging concurrent command delivery", e);
        }
    }

    /**
     * Generous, because these cases wait on a pane's shell and a matrix lane shares its machine
     * with every other lane. The budget is only ever spent when something is already wrong.
     */
    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static TmuxTransport borrowing(java.util.function.Function<CommandRequest, CommandResult> execute) {
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return execute.apply(request);
            }

            @Override
            public void close() {}
        };
    }
}
