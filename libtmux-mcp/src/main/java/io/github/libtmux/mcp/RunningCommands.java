package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
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
 * traps, and functions. An outer subshell arms an exit trap first; that trap prints the numeric end
 * marker and signals a private channel through one absolute tmux executable and the live server's
 * exact {@code -S} socket. Waiting is tmux's own {@code wait-for}, so completion is not inferred
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

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> POSIX_SHELLS = Set.of("sh", "ash", "bash", "dash", "ksh", "mksh", "pdksh", "zsh");

    private RunningCommands() {}

    /**
     * @param paneId the pane it ran in
     * @param outcome why the wait ended, which is never simply "successfully"
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
        String currentCommand = PaneInputCohort.resolve(pane).requireSingularCommandPane("run_shell_command");
        requirePosixShell(currentCommand);
        PaneCommandFrame commandFrame = PaneCommandFrame.resolve(call);

        String nonce = "lt" + HexFormat.of().formatHex(bytes());
        String startMark = nonce + "-s";
        String endMark = nonce + "-e";
        String channel = "ch_" + nonce;

        Cursor before = Screen.from(pane).cursor();
        String typed = payload(commandFrame, command, startMark, endMark, channel, suppressHistory);
        Pane freshPane = Targets.pane(server, pane.id().value());
        String freshCommand = PaneInputCohort.resolve(freshPane).requireSingularCommandPane("run_shell_command");
        requirePosixShell(freshCommand);
        freshPane.sendLine(typed);

        long started = System.nanoTime();
        WakeReason wake = server.channel(channel).await(timeout);
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

        Screen.Fresh fresh =
                wake == WakeReason.SERVER_GONE ? null : Screen.since(freshPane, before, Trim.lineBudget(call));
        Framed framed = fresh == null ? new Framed(List.of(), false, null) : frame(fresh.lines(), startMark, endMark);
        Integer status = wake == WakeReason.SIGNALLED ? framed.status() : null;
        Trim.Trimmed trimmed = Trim.tail(framed.lines(), Trim.lineBudget(call));

        return new Ran(
                freshPane.id().value(),
                wake.name(),
                status,
                trimmed.lines(),
                trimmed.truncated(),
                trimmed.dropped(),
                framed.exact(),
                Math.round(seconds * 100) / 100.0,
                Waits.asSeconds(timeout),
                note(wake, framed));
    }

    private static @Nullable String note(WakeReason wake, Framed framed) {
        return switch (wake) {
            case TIMED_OUT ->
                "The command is still running; the output above is what it had printed by the "
                        + "deadline. Call wait_for_text or capture_since on this pane to keep watching, "
                        + "or send_keys with 'C-c' to stop it.";
            case SERVER_GONE ->
                "The tmux server ended while the command was running. Nothing this call was "
                        + "waiting on can be relied on; check that this process selected the intended socket.";
            case SIGNALLED ->
                framed.exact()
                        ? null
                        : "The output could not be separated from the shell's echo exactly, so it may include the "
                                + "command line itself. This happens when output outgrew the pane's history.";
        };
    }

    /**
     * The line typed at the pane's own interactive shell.
     *
     * <p>Run in that shell rather than a fresh one, so the command sees the environment a person set
     * up there — a virtualenv, a loaded module, a directory someone changed into.
     *
     * <p>The leading space is a request, not a guarantee: bash honours it with {@code HISTCONTROL}
     * set to {@code ignorespace} and zsh with {@code HIST_IGNORE_SPACE}, and a shell configured with
     * neither records the line like any other.
     */
    private static String payload(
            PaneCommandFrame frame,
            String command,
            String startMark,
            String endMark,
            String channel,
            boolean suppressHistory) {
        List<String> tmux = frame.client();
        String start = Shell.quoteAll(append(tmux, "display-message", "-p", startMark));
        String end =
                Shell.quoteAll(append(tmux, "display-message", "-p")) + " " + Shell.quote(endMark + ":") + "\"$?\"";
        String signal = Shell.quoteAll(append(tmux, "wait-for", "-S", channel));
        String finish = end + "; " + signal + "; \\exit 0";
        return (suppressHistory ? " " : "")
                + "( \\trap "
                + Shell.quote(finish)
                + " 0; "
                + start
                + "; ( \\eval "
                + Shell.quote(command)
                + " ) )";
    }

    private static List<String> append(List<String> base, String... more) {
        List<String> argv = new ArrayList<>(base);
        argv.addAll(List.of(more));
        return argv;
    }

    /** @param exact whether both markers were found, so what is returned is only the command's output */
    private record Framed(
            List<String> lines, boolean exact, @Nullable Integer status) {}

    /**
     * Keeps what lies strictly between the two marker lines.
     *
     * <p>Matched as complete marker forms after trimming, never by containment: the echo of the
     * payload holds both markers as substrings and must not be mistaken for either.
     */
    private static Framed frame(List<String> lines, String startMark, String endMark) {
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).trim().equals(startMark)) {
                start = index;
                break;
            }
        }

        int end = -1;
        Integer status = null;
        String endPrefix = endMark + ":";
        for (int index = start < 0 ? 0 : start + 1; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (!line.startsWith(endPrefix)) {
                continue;
            }
            try {
                int candidate = Integer.parseInt(line.substring(endPrefix.length()));
                end = index;
                status = candidate;
                if (start >= 0) {
                    break;
                }
            } catch (NumberFormatException ignored) {
                // A wrapped echo can begin with the prefix; only the numeric marker is plumbing.
            }
        }
        if (start < 0) {
            // The frame is gone: output outgrew the history, or the command cleared the screen.
            // Everything that is not obviously plumbing is better than nothing.
            return new Framed(
                    lines.stream()
                            .filter(line -> !line.contains(startMark) && !line.contains(endMark))
                            .toList(),
                    false,
                    status);
        }
        int last = end < 0 ? lines.size() : end;
        return new Framed(List.copyOf(lines.subList(start + 1, last)), end >= 0, status);
    }

    private static byte[] bytes() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        return value;
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
