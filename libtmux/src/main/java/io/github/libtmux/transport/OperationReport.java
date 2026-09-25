package io.github.libtmux.transport;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import kotlin.annotations.jvm.ReadOnly;

/**
 * One command a transport finished or failed to finish.
 *
 * <p>The verbs are the first word of each command. Arguments, standard input, and stdout are not
 * here: they carry session names, socket paths, and whatever a caller typed. {@link #boundedError()}
 * is stderr, cut off, and {@link #toString()} leaves it out.
 *
 * @param id a counter for this transport, so one call can be told from the next
 * @param verbs the commands, without their arguments
 * @param certainty whether tmux never started, finished, or might have applied the command
 * @param exitCode the process exit status, empty when the process did not finish
 * @param stdoutLines how many stdout lines came back
 * @param stderrLines how many stderr lines came back
 * @param boundedError stderr text, at most {@link #ERROR_LIMIT} characters
 * @param queued how long the call waited for a free process or writer slot
 * @param elapsed how long the command itself ran after that wait
 */
public record OperationReport(
        long id,
        @ReadOnly List<String> verbs,
        DispatchOutcome certainty,
        OptionalInt exitCode,
        int stdoutLines,
        int stderrLines,
        String boundedError,
        Duration queued,
        Duration elapsed) {

    /** How much stderr text a report keeps. */
    public static final int ERROR_LIMIT = 240;

    public OperationReport {
        verbs = List.copyOf(verbs);
        boundedError = bound(boundedError);
    }

    static String bound(String error) {
        if (error.length() <= ERROR_LIMIT) {
            return error;
        }
        return error.substring(0, ERROR_LIMIT);
    }

    /** Joins stderr lines and cuts them at {@link #ERROR_LIMIT}. */
    public static String bound(List<String> stderr) {
        return bound(String.join("\n", stderr));
    }

    /** Counts and timings only. The error text stays on {@link #boundedError()}. */
    @Override
    public String toString() {
        return "OperationReport[id=" + id + ", verbs=" + verbs + ", certainty=" + certainty + ", exit="
                + (exitCode.isPresent() ? exitCode.getAsInt() : "") + ", stdoutLines=" + stdoutLines
                + ", stderrLines=" + stderrLines + ", queued=" + queued + ", elapsed=" + elapsed + "]";
    }
}
