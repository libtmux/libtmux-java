package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
 * <p>The command is followed by two things the shell runs after it: one that records the exit
 * status in a pane option, and one that signals a private tmux channel. Waiting is then tmux's own
 * {@code wait-for}, which blocks server-side and returns on the signal itself — nothing is inferred
 * from what the screen looks like.
 *
 * <h2>How the output is separated from the plumbing</h2>
 *
 * <p>The shell echoes everything typed at it, so the plumbing appears on screen alongside the
 * output. It is cut out by framing: the command is bracketed by two lines that print a random
 * nonce, and only lines strictly between them are returned. The echo of the whole payload
 * <em>contains</em> the nonce, but no echo is ever <em>equal</em> to it, so exact-equality matching
 * separates the two — including when the echo wraps across several rows, which is the case that
 * defeats matching the plumbing by its shape.
 */
final class RunningCommands {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunningCommands() {}

    /**
     * @param paneId the pane it ran in
     * @param outcome why the wait ended, which is never simply "successfully"
     * @param exitStatus the command's status, absent when it had not finished
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

        String nonce = "lt" + HexFormat.of().formatHex(bytes());
        String startMark = nonce + "-s";
        String endMark = nonce + "-e";
        String channel = "ch_" + nonce;
        String statusOption = "@st_" + nonce;

        Cursor before = Watching.from(pane).cursor();
        String typed =
                payload(server, pane, command, nonce, startMark, endMark, statusOption, channel, suppressHistory);
        // Literal, so a command that happens to spell a key name — "Enter", "C-c" — is typed rather
        // than pressed. Enter is a separate send because it is the one keypress that is meant.
        server.run(List.of("send-keys", "-l", "-t", pane.id().value(), typed));
        server.run(List.of("send-keys", "-t", pane.id().value(), "Enter"));

        long started = System.nanoTime();
        WakeReason wake = server.waitFor(channel, timeout);
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

        Integer status = wake == WakeReason.SIGNALLED ? readStatus(pane, statusOption) : null;
        Watching.Fresh fresh = wake == WakeReason.SERVER_GONE ? null : Watching.since(pane, before);
        Framed framed = fresh == null ? new Framed(List.of(), false) : frame(fresh.lines(), startMark, endMark);
        Trim.Trimmed trimmed = Trim.tail(framed.lines(), Trim.lineBudget(call));

        return new Ran(
                pane.id().value(),
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
                        + "deadline. Call tmux_wait_for_text or tmux_capture_since on this pane to keep watching, "
                        + "or tmux_send_keys with 'C-c' to stop it.";
            case SERVER_GONE ->
                "The tmux server ended while the command was running. Nothing this call was "
                        + "waiting on can be relied on; call tmux_list_servers to see what is left.";
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
            Server server,
            Pane pane,
            String command,
            String nonce,
            String startMark,
            String endMark,
            String statusOption,
            String channel,
            boolean suppressHistory) {
        // The config file is left off: it is read when a server starts and means nothing to a command
        // sent to one already running. Everything typed here is echoed by the shell onto the pane a
        // person may be watching, so the shortest correct command line is the kindest one.
        List<String> tmux = new ArrayList<>(List.of(server.config().binary()));
        tmux.addAll(server.config().endpoint().flags());

        // One tmux invocation carrying two commands rather than two invocations. tmux ends a command
        // at a bare ';' argument, and halving the invocations halves what the shell echoes back.
        String finish =
                Shell.quoteAll(append(tmux, "set-option", "-p", "-t", pane.id().value(), statusOption))
                        + " \"$" + nonce + "\" " + Shell.quote(";") + " "
                        + Shell.quoteAll(List.of("wait-for", "-S", channel));

        // The status is held in a shell variable named for the nonce, so nothing this types can
        // collide with a variable the person using the pane already had.
        return (suppressHistory ? " " : "") + "echo " + startMark + "; ( " + command + " ); " + nonce + "=$?; echo "
                + endMark + "; " + finish;
    }

    private static List<String> append(List<String> base, String... more) {
        List<String> argv = new ArrayList<>(base);
        argv.addAll(List.of(more));
        return argv;
    }

    private static @Nullable Integer readStatus(Pane pane, String option) {
        Optional<String> recorded = pane.options().get(option);
        try {
            return recorded.map(String::trim).map(Integer::parseInt).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        } finally {
            try {
                pane.options().unset(option);
            } catch (RuntimeException e) {
                // A leftover pane option costs nothing and is gone with the pane; failing the call
                // over tidying up would throw away the answer the caller came for.
            }
        }
    }

    /** @param exact whether both markers were found, so what is returned is only the command's output */
    private record Framed(List<String> lines, boolean exact) {}

    /**
     * Keeps what lies strictly between the two marker lines.
     *
     * <p>Matched by equality after trimming, never by containment: the echo of the payload holds
     * both markers as substrings and must not be mistaken for either.
     */
    private static Framed frame(List<String> lines, String startMark, String endMark) {
        int start = -1;
        int end = -1;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (start < 0 && line.equals(startMark)) {
                start = index;
            } else if (start >= 0 && line.equals(endMark)) {
                end = index;
                break;
            }
        }
        if (start < 0) {
            // The frame is gone: output outgrew the history, or the command cleared the screen.
            // Everything that is not obviously plumbing is better than nothing.
            return new Framed(
                    lines.stream().filter(line -> !line.contains(startMark)).toList(), false);
        }
        int last = end < 0 ? lines.size() : end;
        return new Framed(List.copyOf(lines.subList(start + 1, last)), end >= 0);
    }

    private static byte[] bytes() {
        byte[] value = new byte[5];
        RANDOM.nextBytes(value);
        return value;
    }
}
