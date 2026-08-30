package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
 * <p>The command is followed by two things the shell runs after it: an end marker carrying the exit
 * status, and a signal on a private tmux channel. Waiting is then tmux's own {@code wait-for}, which
 * blocks server-side and returns on the signal itself — completion is not inferred from what the
 * screen looks like.
 *
 * <h2>How the output is separated from the plumbing</h2>
 *
 * <p>The shell echoes everything typed at it, so the plumbing appears on screen alongside the
 * output. It is cut out by framing: the command is bracketed by two lines that print a random
 * nonce, and only lines strictly between them are returned. The echo of the whole payload contains
 * the nonce, but never as a complete start marker or an end marker followed only by a numeric
 * status. Matching those forms separates the two even when the echo wraps across several rows.
 */
final class RunningCommands {

    private static final SecureRandom RANDOM = new SecureRandom();

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

        String nonce = "lt" + HexFormat.of().formatHex(bytes());
        String startMark = nonce + "-s";
        String endMark = nonce + "-e";
        String channel = "ch_" + nonce;

        try (StagedCommand staged = StagedCommand.create(command)) {
            Cursor before = Screen.from(pane).cursor();
            String typed = payload(server, staged.path(), nonce, startMark, endMark, channel, suppressHistory);
            // Never make the shell wait for Java cleanup: a transport can report UNKNOWN after tmux
            // accepted this line, and that failure must not strand the pane at private plumbing.
            pane.sendLine(typed);

            long started = System.nanoTime();
            WakeReason wake = server.channel(channel).await(timeout);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

            Screen.Fresh fresh =
                    wake == WakeReason.SERVER_GONE ? null : Screen.since(pane, before, Trim.lineBudget(call));
            Framed framed =
                    fresh == null ? new Framed(List.of(), false, null) : frame(fresh.lines(), startMark, endMark);
            Integer status = wake == WakeReason.SIGNALLED ? framed.status() : null;
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
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the pane command", e);
        }
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
            Path command,
            String nonce,
            String startMark,
            String endMark,
            String channel,
            boolean suppressHistory) {
        // The config file is left off: it is read when a server starts and means nothing to a command
        // sent to one already running. Everything typed here is echoed by the shell onto the pane a
        // person may be watching, so the shortest correct command line is the kindest one.
        // A resolved path, not the name: the pane resolves a name against the user's PATH, and a
        // client from another release than this server is dropped without delivering the signal.
        List<String> tmux = new ArrayList<>(List.of(server.config().binaryPath()));
        tmux.addAll(server.config().endpoint().flags());

        String finish = Shell.quoteAll(append(tmux, "wait-for", "-S", channel));

        // The status is held in a shell variable named for the nonce, so nothing this types can
        // collide with a variable the person using the pane already had.
        return (suppressHistory ? " " : "") + "echo " + startMark + "; ( . " + Shell.quote(command.toString()) + " ); "
                + nonce + "=$?; echo " + endMark + ":\"$" + nonce + "\"; " + finish;
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
        byte[] value = new byte[5];
        RANDOM.nextBytes(value);
        return value;
    }

    private record StagedCommand(Path path) implements AutoCloseable {

        private static StagedCommand create(String command) throws IOException {
            Path path = Files.createTempFile(
                    "libtmux-java-command-",
                    ".sh",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(path, command, StandardCharsets.UTF_8);
                return new StagedCommand(path);
            } catch (IOException failure) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
