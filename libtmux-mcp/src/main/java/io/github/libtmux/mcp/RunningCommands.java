package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneCommand;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.transport.DispatchOutcome;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Runs a command in a pane and waits for it, in one call.
 *
 * <p>This is the tool a model should reach for whenever it wrote the command itself. The
 * alternative — send it, then look at the screen repeatedly to guess whether it finished — moves
 * the wait into the agent's turn loop, where it has no ceiling, costs a call per look, and still
 * cannot tell a finished command from a stalled one.
 *
 * <h2>How completion is known</h2>
 *
 * <p>An inner subshell evaluates the command with the pane shell's inherited environment, options,
 * traps, and functions. An outer subshell arms a trap first — for interrupt and terminate as well as
 * exit, so a command stopped at the pane still reports what it was stopped with; that trap prints the
 * numeric end marker and signals a private channel through one absolute tmux executable and the live
 * server's exact {@code -S} socket. Waiting is tmux's own {@code wait-for}, so completion is not inferred
 * from the screen.
 *
 * <h2>How the output is separated from the plumbing</h2>
 *
 * <p>The shell echoes everything typed at it, so the plumbing appears on screen alongside the
 * output. It is cut out by framing: the command is bracketed by two lines that print a random
 * nonce, and only lines strictly between them are returned. The echo of the whole payload contains
 * the nonce, but never as a complete start marker or an end marker followed only by a numeric
 * status. Matching those forms separates the two even when the echo wraps across several rows.
 * Ordinary aliases and functions such as {@code echo}, {@code printf}, or {@code tmux} cannot own
 * the marker path. A parent shell that already replaces {@code trap}, {@code eval}, {@code exit},
 * or the exact resolved executable with a same-name function is outside the supported boundary.
 */
final class RunningCommands {

    private static final Set<String> POSIX_SHELLS = Set.of("sh", "ash", "bash", "dash", "ksh", "mksh", "pdksh", "zsh");

    private RunningCommands() {}

    /**
     * @param paneId the pane it ran in
     * @param outcome why the wait ended, which is never simply "successfully": SIGNALLED, TIMED_OUT,
     *     PANE_GONE or SERVER_GONE
     * @param exitStatus the command's status, absent when it had not finished or its marker was lost
     * @param output what the command printed, plumbing removed
     * @param truncated whether older output was dropped to fit the budget
     * @param linesDropped how many lines that cost
     * @param framed whether the plumbing could be cut out exactly
     * @param seconds how long the wait took
     * @param effectiveTimeout the ceiling actually enforced, which may be lower than the one asked for
     * @param note what a caller should know that the fields above do not say
     */
    record Ran(
            String paneId,
            String outcome,
            @Nullable Integer exitStatus,
            List<String> output,
            boolean truncated,
            int linesDropped,
            boolean framed,
            double seconds,
            double effectiveTimeout,
            @Nullable String note) {}

    static Ran run(Call call) {
        Server server = call.server();
        Pane pane = Targets.pane(server, call.string("pane_id"));
        String command = call.string("command");
        Duration timeout = Waits.requested(call);
        boolean suppressHistory = call.flag("suppress_history", true);
        PaneInputCohort.Resolution initial = PaneInputCohort.resolve(pane, call.caller());
        String currentCommand = initial.requireSingularCommandPane("run_shell_command");
        requirePosixShell(currentCommand);
        PaneInputReservations.Lease lease = PaneInputReservations.run(pane, initial, "run_shell_command");
        boolean retained = false;
        AtomicBoolean possiblyDispatched = new AtomicBoolean();
        Pane freshPane = pane;
        PaneCommand run = PaneCommand.fresh();
        try {
            PaneCommandFrame commandFrame = PaneCommandFrame.resolve(call);

            Cursor before = Screen.from(pane).cursor();
            // The framing is the library's own, so a run here and Pane.run are read back the same
            // way; only the orchestration around it — the lease other clients wait on — is this server's.
            String line = run.typed(commandFrame.client(), command);
            String typed = suppressHistory ? line : line.stripLeading();
            freshPane = Targets.pane(server, pane.id().value());
            Pane target = freshPane;
            try {
                freshPane.sendLiteral(List.of(typed + "\r"), () -> {
                    String freshCommand = lease.requireSameRun(PaneInputCohort.resolve(target, call.caller()));
                    requirePosixShell(freshCommand);
                    possiblyDispatched.set(true);
                });
            } catch (DispatchException failure) {
                if (failure.outcome() == DispatchOutcome.NOT_DISPATCHED) {
                    possiblyDispatched.set(false);
                }
                throw failure;
            }

            long started = System.nanoTime();
            WakeReason wake;
            PaneWatch watch = PaneWatch.over(freshPane, before, Trim.lineBudget(call), () -> {
                try {
                    server.channel(run.channel()).signal();
                } catch (RuntimeException unsignalled) {
                    // The wait still ends at its own deadline.
                }
            });
            try {
                wake = server.channel(run.channel()).await(timeout);
            } catch (InterruptedException cancelled) {
                throw Waits.cancelled(cancelled);
            } finally {
                watch.close();
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

            Screen.Fresh fresh = null;
            Ends ended = watch.ended();
            if (wake != WakeReason.SERVER_GONE && ended == null) {
                try {
                    fresh = Screen.since(freshPane, before, Trim.lineBudget(call));
                } catch (LibTmuxException unread) {
                    // The pane or the server went away between the signal and this read.
                    ended = Ends.of(freshPane, unread);
                }
            }
            if (fresh == null) {
                // Nothing can be read from what is gone; the last look is the output so far.
                fresh = watch.last();
            }
            PaneCommand.Framed framed = fresh == null
                    ? new PaneCommand.Framed(List.of(), false, OptionalInt.empty())
                    : run.frame(fresh.lines());
            Integer status = wake == WakeReason.SIGNALLED
                            && ended == null
                            && framed.status().isPresent()
                    ? framed.status().getAsInt()
                    : null;
            String outcome = ended == Ends.PANE_GONE && wake != WakeReason.SERVER_GONE ? "PANE_GONE" : wake.name();
            Trim.Trimmed trimmed = Trim.tail(framed.lines(), Trim.lineBudget(call));
            if (status == null) {
                retained = true;
                PaneRunSettlement.retain(lease, freshPane, run);
            }

            return new Ran(
                    freshPane.id().value(),
                    outcome,
                    status,
                    trimmed.lines(),
                    trimmed.truncated(),
                    trimmed.dropped(),
                    framed.exact(),
                    Math.round(seconds * 100) / 100.0,
                    Waits.asSeconds(timeout),
                    note(outcome, framed));
        } catch (RuntimeException failure) {
            if (possiblyDispatched.get() && !retained) {
                retained = true;
                PaneRunSettlement.retain(lease, freshPane, run);
            }
            throw failure;
        } finally {
            if (!retained) {
                lease.close();
            }
        }
    }

    private static @Nullable String note(String outcome, PaneCommand.Framed framed) {
        return switch (outcome) {
            case "TIMED_OUT" ->
                "The command is still running; the output above is what it had printed by the "
                        + "deadline. Call wait_for_text or capture_since on this pane to keep watching, "
                        + "or send_keys with 'C-c' to stop it.";
            case "PANE_GONE" ->
                "The pane was killed or exited while the command was running, so it did not finish. The "
                        + "output above is what it had printed by the last look at the pane.";
            case "SERVER_GONE" ->
                "The tmux server ended while the command was running. Nothing this call was "
                        + "waiting on can be relied on; check that this process selected the intended socket.";
            default ->
                framed.exact()
                        ? null
                        : "The output could not be separated from the shell's echo exactly, so it may include the "
                                + "command line itself. This happens when output outgrew the pane's history.";
        };
    }

    private static void requirePosixShell(String current) {
        int slash = current.lastIndexOf('/');
        String name = slash < 0 ? current : current.substring(slash + 1);
        if (name.startsWith("-")) {
            name = name.substring(1);
        }
        if (!POSIX_SHELLS.contains(name)) {
            throw new IllegalStateException("run_shell_command requires a POSIX-compatible shell in the target pane; "
                    + "it is running '" + name
                    + "'. Use send_keys when typing into another program is intentional");
        }
    }
}
