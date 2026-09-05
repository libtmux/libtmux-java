package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Running a command and knowing how it ended, against real tmux.
 *
 * <p>The whole point of this tool is that a model does not have to guess. These cases are the ways
 * guessing goes wrong: a command that failed, one still running at the deadline, and output that
 * has to be told apart from the shell's echo of the plumbing that waits for it.
 */
@ExtendWith(TmuxExtension.class)
final class RunningCommandsTest {

    private static final Pattern NONCE = Pattern.compile("\\blt[0-9a-f]{32}\\b");

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
    void callerPaneRefusesBeforeRunSetup(Server server) {
        String pane = server.panes().getFirst().id().value();
        String marker = "caller-run-marker";

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> RunningCommands.run(
                        TestCalls.asCaller(server, pane, "pane_id", pane, "command", "echo " + marker)));

        assertTrue(String.valueOf(refused.getMessage()).contains(pane), refused.getMessage());
        assertFalse(capture(server, pane).contains(marker));
    }

    @Test
    void newlyAttendedPaneRefusesAtFinalPreflight(Server server) throws Exception {
        Pane pane = server.panes().getFirst();
        String marker = "attended-transition-marker";
        AtomicBoolean attached = new AtomicBoolean();
        AtomicReference<Process> client = new AtomicReference<>();
        CopyOnWriteArrayList<CommandRequest> requests = new CopyOnWriteArrayList<>();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport transitioning = borrowing(request -> {
                CommandResult result = processes.execute(request);
                requests.add(request);
                if (hasCommand(request, "capture-pane") && attached.compareAndSet(false, true)) {
                    client.set(attachClient(server, pane));
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), transitioning)) {
                IllegalStateException refused = assertThrows(
                        IllegalStateException.class,
                        () -> RunningCommands.run(
                                TestCalls.on(measured, "pane_id", pane.id().value(), "command", "echo " + marker)));
                assertTrue(
                        String.valueOf(refused.getMessage()).contains(pane.id().value()), refused.getMessage());
            }
        } finally {
            server.clients().forEach(io.github.libtmux.Client::detach);
            Process process = client.get();
            if (process != null && !process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }

        assertEquals(0, commandCount(requests, "send-keys"));
        assertEquals(0, commandCount(requests, "wait-for"));
        assertFalse(capture(server, pane.id().value()).contains(marker));
    }

    @ParameterizedTest
    @ValueSource(strings = {"echo(){ :; }; alias printf=:", "alias echo=:; printf(){ :; }"})
    void preexistingOutputShadowsCannotHideCompletion(String shadow, Server server, @TempDir Path temporary)
            throws Exception {
        Pane pane = shellPane(server, "output-shadow", "/bin/dash");
        ready(
                pane,
                temporary.resolve("output-shadow"),
                "frame_value=kept; frame_helper(){ test \"$frame_value\" = kept; }; " + shadow);

        RunningCommands.Ran ran = RunningCommands.run(TestCalls.on(
                server, "pane_id", pane.id().value(), "command", "frame_helper || exit 91; exit 7", "timeout", 5));

        assertCompleted(ran, 7);
    }

    @Test
    void ordinaryClientNameShadowsDoNotOwnFraming(Server server, @TempDir Path temporary) throws Exception {
        Pane pane = shellPane(server, "client-shadow", "/bin/dash");
        String client = PaneCommandFrame.resolve(TestCalls.on(server)).client().getFirst();
        ready(pane, temporary.resolve("client-shadow"), "tmux(){ :; }; alias " + Shell.quote(client + "=:"));

        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane.id().value(), "command", "exit 4"));

        assertCompleted(ran, 4);
    }

    @Test
    void commandDefinedFramingNamesStayInTheInnerShell(Server server) {
        Pane pane = shellPane(server, "defined-frame", "/bin/bash", "--noprofile", "--norc");
        String client = PaneCommandFrame.resolve(TestCalls.on(server)).client().getFirst();
        String definitions = "function trap { :; }; function eval { :; }; function exit { :; }; function "
                + client
                + " { :; }; false";

        RunningCommands.Ran defined =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane.id().value(), "command", definitions));
        RunningCommands.Ran after =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane.id().value(), "command", "true"));

        assertCompleted(defined, 1);
        assertCompleted(after, 0);
    }

    @Test
    void inheritedErrexitAndXtraceKeepThePaneAlive(Server server, @TempDir Path temporary) throws Exception {
        Pane pane = shellPane(server, "errexit", "/bin/bash", "--noprofile", "--norc");
        Path forbidden = temporary.resolve("must-not-exist");
        ready(pane, temporary.resolve("errexit"), "set -ex");

        String command = "false; : > " + Shell.quote(forbidden.toString());
        RunningCommands.Ran ran = RunningCommands.run(
                TestCalls.on(server, "pane_id", pane.id().value(), "command", command, "timeout", 5));

        assertCompleted(ran, 1);
        assertFalse(Files.exists(forbidden), "errexit did not stop the authored sequence");

        pane.sendLine("printf 'parent-alive:%s\\n' \"$-\"");
        assertTrue(await(() -> pane.capture().stream()
                .map(String::trim)
                .anyMatch(line -> line.matches("parent-alive:.*e.*x.*|parent-alive:.*x.*e.*"))));
    }

    @Test
    void framingUsesNoPaneStatusVariableAndIgnoresReadonlyCollision(Server server) throws Exception {
        Pane pane = server.panes().get(0);
        CopyOnWriteArrayList<String> nonces = new CopyOnWriteArrayList<>();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = borrowing(request -> {
                nonce(request)
                        .filter(nonces::addIfAbsent)
                        .filter(value -> nonces.size() == 2)
                        .ifPresent(value -> armReadonly(pane, value));
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), recording)) {
                RunningCommands.Ran first = RunningCommands.run(
                        TestCalls.on(measured, "pane_id", pane.id().value(), "command", "exit 5"));
                assertCompleted(first, 5);

                String captured = nonces.getFirst();
                String inspect = "if [ \"${" + captured + "+set}\" = set ]; then "
                        + "printf 'set\\n'; else printf 'unset\\n'; fi";
                RunningCommands.Ran inspected = RunningCommands.run(
                        TestCalls.on(server, "pane_id", pane.id().value(), "command", inspect));
                assertEquals(List.of("unset"), inspected.output(), "the frame leaked its status name");

                RunningCommands.Ran collided = RunningCommands.run(
                        TestCalls.on(measured, "pane_id", pane.id().value(), "command", "exit 6", "timeout", 5));

                assertEquals(2, nonces.size(), "the collision nonce was not captured");
                assertCompleted(collided, 6);
            }
        }
    }

    @Test
    void aSynchronizedCommandIsRefusedBeforeTyping(Server server) {
        Pane source = server.panes().getFirst();
        Pane peer = source.split();
        source.window().setSynchronizePanes(true);
        String marker = "synchronized-run-marker";

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> RunningCommands.run(
                        TestCalls.on(server, "pane_id", source.id().value(), "command", "printf '" + marker + "\\n'")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("run_shell_command"), message);
        assertTrue(message.contains("one"), message);
        assertFalse(capture(server, source.id().value()).contains(marker));
        assertFalse(capture(server, peer.id().value()).contains(marker));
    }

    @Test
    void aModalEffectiveCommandRecipientIsRefusedBeforeBaselineOrTyping(Server server) {
        Pane source = server.panes().getFirst();
        Pane modal = source.split();
        source.window().setSynchronizePanes(true);
        modal.copyMode();

        assertRefusedBeforeRunWork(
                server, source, "modal-must-not-run", modal.id().value());
    }

    @Test
    void aDeadCommandPaneIsRefusedBeforeBaselineOrTyping(Server server) throws Exception {
        Pane source = server.panes().getFirst();
        source.split();
        source.options().set("remain-on-exit", "on");
        source.sendLine("exit");
        assertTrue(await(() -> "1".equals(source.expand("#{pane_dead}"))), "the pane did not become dead");

        assertRefusedBeforeRunWork(
                server, source, "dead-must-not-run", "dead", source.id().value());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RunTransition.class)
    void stateTransitionAfterSetupRefusesRun(RunTransition transition, Server server) throws Exception {
        Pane source = server.panes().getFirst();
        prepare(transition, source);
        AtomicInteger listings = new AtomicInteger();
        CopyOnWriteArrayList<CommandRequest> requests = new CopyOnWriteArrayList<>();

        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport changing = borrowing(request -> {
                requests.add(request);
                if (request.commands().stream().anyMatch(RunningCommandsTest::isCohortListing)
                        && listings.incrementAndGet() == 2) {
                    apply(transition, server, source);
                }
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), changing)) {
                assertThrows(
                        RuntimeException.class,
                        () -> RunningCommands.run(TestCalls.on(
                                measured, "pane_id", source.id().value(), "command", "echo transition-must-not-run")));
            } finally {
                restore(transition, server, source);
            }
        }

        assertEquals(2, listings.get(), "the run must attempt both authoritative preflights");
        assertEquals(0, commandCount(requests, "send-keys"));
        assertEquals(0, commandCount(requests, "wait-for"));
        assertTrue(server.buffers().list().stream()
                .noneMatch(buffer -> buffer.name().startsWith("libtmux-run-")));
        assertTrue(server.panes().stream()
                .flatMap(pane -> pane.options().all().keySet().stream())
                .noneMatch(name -> name.startsWith("@st_")));
    }

    @Test
    void successfulRunUsesExactlyTwoPreflights(Server server) {
        String pane = server.panes().getFirst().id().value();
        CopyOnWriteArrayList<CommandRequest> requests = new CopyOnWriteArrayList<>();

        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = borrowing(request -> {
                requests.add(request);
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), recording)) {
                RunningCommands.Ran ran =
                        RunningCommands.run(TestCalls.on(measured, "pane_id", pane, "command", "true"));
                assertCompleted(ran, 0);
            }
        }

        List<Integer> preflights = requestIndexes(requests, RunningCommandsTest::isCohortListing);
        List<Integer> discoveries = requestIndexes(requests, RunningCommandsTest::isSocketDiscovery);
        List<Integer> sends = requestIndexes(requests, request -> hasCommand(request, "send-keys"));
        assertEquals(2, preflights.size());
        assertEquals(1, discoveries.size());
        assertEquals(1, sends.size());
        assertTrue(preflights.get(0) < discoveries.getFirst());
        assertTrue(discoveries.getFirst() < preflights.get(1));
        assertEquals(preflights.get(1) + 1, sends.getFirst(), "a tmux query followed the final preflight");
        assertEquals(1, directCommandCount(requests, "wait-for"));
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
        String pane = shellPane(server, "timed-output", "/bin/dash").id().value();

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
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport uncertain = borrowing(request -> {
                CommandResult result = processes.execute(request);
                if (request.commands().get(0).stream().anyMatch(argument -> argument.contains("ch_lt"))) {
                    throw new TmuxTransportException("simulated failure after delivery", DispatchOutcome.UNKNOWN, null);
                }
                return result;
            });
            try (Server measured = Server.using(server.config(), uncertain)) {
                assertThrows(
                        TmuxTransportException.class,
                        () -> RunningCommands.run(TestCalls.on(
                                measured,
                                "pane_id",
                                pane,
                                "command",
                                "printf ran > " + Shell.quote(accepted.toString()))));
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
    void aCommandCannotChangeThePanesShellAndCannotEndIt(Server server, @TempDir Path temporary) {
        String pane = server.panes().get(0).id().value();

        String changed = Shell.quote(temporary.toString());
        String mutation = "mine=set; mine_helper(){ :; }; trap 'mine_trap=ran' 0; cd " + changed
                + "; export mine_export=set; exit 3";
        RunningCommands.Ran exited = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", mutation));
        RunningCommands.Ran commented =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", "echo comment-safe # comment"));
        RunningCommands.Ran parenthesis =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", ": ); exit 7; #"));
        String inspect = "if [ \"${mine+set}\" = set ] || command -v mine_helper >/dev/null 2>&1 "
                + "|| [ \"${mine_trap+set}\" = set ] || [ \"${mine_export+set}\" = set ] "
                + "|| [ \"$PWD\" = " + changed + " ]; then printf 'leaked\\n'; else printf 'clean\\n'; fi";
        RunningCommands.Ran after = RunningCommands.run(TestCalls.on(server, "pane_id", pane, "command", inspect));

        assertEquals(3, exited.exitStatus(), "exit reports from the isolated command");
        assertEquals(java.util.List.of("comment-safe"), commented.output(), "a comment cannot hide the framing");
        assertEquals("SIGNALLED", parenthesis.outcome(), "a closing parenthesis cannot escape the command");
        assertEquals(java.util.List.of("clean"), after.output(), "authored state escaped its inner subshell");
        assertEquals(1, server.panes().size(), "and exiting inside it did not take the pane with it");
    }

    /** A variable a person set in the pane themselves must survive the plumbing running around it. */
    @Test
    void thePlumbingDoesNotDisturbThePanesOwnShellVariables(Server server, @TempDir Path temporary) throws Exception {
        Pane pane = server.panes().get(0);
        Path ready = temporary.resolve("parent-state-ready");
        pane.sendLine("theirs=kept; trap 'theirs_trap=kept' USR1; : > " + Shell.quote(ready.toString()));
        assertTrue(await(() -> Files.exists(ready)), "the parent-state setup did not finish");

        RunningCommands.Ran ran =
                RunningCommands.run(TestCalls.on(server, "pane_id", pane.id().value(), "command", "echo \"$theirs\""));

        assertEquals(java.util.List.of("kept"), ran.output());
        pane.sendLine("kill -USR1 $$; printf 'parent-trap:%s\\n' \"$theirs_trap\"");
        assertTrue(
                await(() -> pane.capture().stream().map(String::trim).anyMatch("parent-trap:kept"::equals)),
                "the frame disturbed the parent shell's trap");
    }

    @Test
    void aPaneThatIsNotThereSaysWhichToolFindsOne(Server server) {
        ObjectDoesNotExist refused = assertThrows(
                ObjectDoesNotExist.class,
                () -> RunningCommands.run(TestCalls.on(server, "pane_id", "%999", "command", "true")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("list_panes"), message);
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
                var first = calls.submit(() -> RunningCommands.run(
                        TestCalls.on(measured, "pane_id", pane, "command", "printf 'first-run-marker\\n'")));
                var second = calls.submit(() -> RunningCommands.run(
                        TestCalls.on(measured, "pane_id", pane, "command", "printf 'second-run-marker\\n'")));

                assertEquals(
                        java.util.List.of("first-run-marker"),
                        first.get(Waits.DEFAULT.toSeconds(), TimeUnit.SECONDS).output());
                assertEquals(
                        java.util.List.of("second-run-marker"),
                        second.get(Waits.DEFAULT.toSeconds(), TimeUnit.SECONDS).output());
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

    private static Process attachClient(Server server, Pane pane) {
        String socket =
                server.cmd("display-message", "-p", "#{socket_path}").stdout().getFirst();
        String command = Shell.quote(server.config().binary()) + " -S " + Shell.quote(socket) + " attach-session -t "
                + Shell.quote(pane.window().session().id().value());
        try {
            ProcessBuilder builder = new ProcessBuilder("script", "-q", "-c", command, "/dev/null")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("TERM", "xterm");
            Process process = builder.start();
            if (!await(() -> !server.clients().isEmpty())) {
                process.destroyForcibly();
                throw new IllegalStateException("the attended client did not attach");
            }
            return process;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("could not attach the attended test client", failure);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not attach the attended test client", failure);
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

    private static Pane shellPane(Server server, String name, String... command) {
        return server.sessions()
                .getFirst()
                .newWindow(window -> window.named(name).running(command))
                .panes()
                .getFirst();
    }

    private static void ready(Pane pane, Path marker, String setup) throws InterruptedException {
        pane.sendLine(setup + "; : > " + Shell.quote(marker.toString()));
        assertTrue(await(() -> Files.exists(marker)), "the pane setup did not finish");
    }

    private static void assertCompleted(RunningCommands.Ran ran, int status) {
        assertEquals("SIGNALLED", ran.outcome());
        assertEquals(status, ran.exitStatus());
        assertTrue(ran.framed(), "completion was not framed exactly");
    }

    private static void armReadonly(Pane pane, String nonce) {
        pane.sendLine("readonly " + nonce + "=held; printf 'nonce-armed\\n'");
        try {
            assertTrue(
                    await(() -> pane.capture().stream().map(String::trim).anyMatch("nonce-armed"::equals)),
                    "the nonce collision setup did not finish");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while arming the nonce collision", failure);
        }
    }

    private static Optional<String> nonce(CommandRequest request) {
        return request.commands().getFirst().stream()
                .filter(argument -> argument.contains("ch_lt"))
                .flatMap(argument -> NONCE.matcher(argument).results())
                .map(MatchResult::group)
                .findFirst();
    }

    private static String capture(Server server, String paneId) {
        return String.join("\n", server.cmd("capture-pane", "-p", "-t", paneId).stdout());
    }

    private static void assertRefusedBeforeRunWork(
            Server server, Pane source, String marker, String... expectedMessageParts) {
        CopyOnWriteArrayList<CommandRequest> requests = new CopyOnWriteArrayList<>();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = borrowing(request -> {
                requests.add(request);
                return processes.execute(request);
            });
            try (Server measured = Server.using(server.config(), recording)) {
                IllegalStateException refused = assertThrows(
                        IllegalStateException.class,
                        () -> RunningCommands.run(
                                TestCalls.on(measured, "pane_id", source.id().value(), "command", "echo " + marker)));
                String message = String.valueOf(refused.getMessage());
                assertTrue(message.contains("run_shell_command"), message);
                for (String part : expectedMessageParts) {
                    assertTrue(message.contains(part), message);
                }
            }
        }
        assertNoRunWork(requests);
    }

    private static void assertNoRunWork(List<CommandRequest> requests) {
        assertEquals(0, commandCount(requests, "capture-pane"));
        assertEquals(0, commandCount(requests, "send-keys"));
        assertEquals(0, commandCount(requests, "wait-for"));
    }

    private static int commandCount(List<CommandRequest> requests, String name) {
        return Math.toIntExact(
                requests.stream().filter(request -> hasCommand(request, name)).count());
    }

    private static int directCommandCount(List<CommandRequest> requests, String name) {
        return Math.toIntExact(requests.stream()
                .filter(request -> request.commands().stream()
                        .anyMatch(command -> command.getFirst().equals(name)))
                .count());
    }

    private static List<Integer> requestIndexes(
            List<CommandRequest> requests, java.util.function.Predicate<CommandRequest> predicate) {
        return java.util.stream.IntStream.range(0, requests.size())
                .filter(index -> predicate.test(requests.get(index)))
                .boxed()
                .toList();
    }

    private static boolean hasCommand(CommandRequest request, String name) {
        return request.commands().stream()
                .anyMatch(command -> command.getFirst().equals(name)
                        || command.stream().anyMatch(argument -> argument.contains("'" + name + "'")));
    }

    private static boolean isCohortListing(CommandRequest request) {
        return request.commands().stream().anyMatch(RunningCommandsTest::isCohortListing);
    }

    private static boolean isCohortListing(List<String> command) {
        if (!command.getFirst().equals("list-panes") || !command.contains("-t") || !command.contains("-F")) {
            return false;
        }
        String format = command.get(command.indexOf("-F") + 1);
        return List.of("pane_id", "pane_synchronized", "pane_in_mode", "pane_dead", "pane_current_command").stream()
                .allMatch(format::contains);
    }

    private static boolean isSocketDiscovery(CommandRequest request) {
        return request.commands().stream()
                .anyMatch(command -> command.equals(List.of("display-message", "-p", "#{socket_path}")));
    }

    private static void prepare(RunTransition transition, Pane source) {
        switch (transition) {
            case MODE -> {}
            case DEAD, NON_SHELL -> source.options().set("remain-on-exit", "on");
            case PLURAL -> {
                source.split();
                source.window().setSynchronizePanes(true);
                source.options().set("synchronize-panes", "off");
            }
            case DISAPPEAR -> source.split();
        }
    }

    private static void apply(RunTransition transition, Server server, Pane source) {
        switch (transition) {
            case MODE -> source.copyMode();
            case DEAD -> {
                source.sendLine("exit");
                awaitUnchecked(() -> "1".equals(source.expand("#{pane_dead}")));
            }
            case PLURAL -> source.options().set("synchronize-panes", "on");
            case NON_SHELL -> {
                source.sendLine("exec cat");
                awaitUnchecked(() -> "cat".equals(source.expand("#{pane_current_command}")));
            }
            case DISAPPEAR -> server.cmd("kill-pane", "-t", source.id().value());
        }
    }

    private static void restore(RunTransition transition, Server server, Pane source) {
        switch (transition) {
            case MODE -> server.cmd("send-keys", "-t", source.id().value(), "-X", "cancel");
            case DEAD, NON_SHELL -> {
                server.cmd("respawn-pane", "-k", "-t", source.id().value());
                source.options().unset("remain-on-exit");
            }
            case PLURAL -> {
                source.window().setSynchronizePanes(false);
                source.options().unset("synchronize-panes");
            }
            case DISAPPEAR -> {}
        }
    }

    private enum RunTransition {
        MODE,
        DEAD,
        PLURAL,
        NON_SHELL,
        DISAPPEAR
    }

    private static void awaitUnchecked(BooleanSupplier condition) {
        try {
            if (!await(condition)) {
                throw new IllegalStateException("timed out arranging run transition");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while arranging run transition", failure);
        }
    }
}
