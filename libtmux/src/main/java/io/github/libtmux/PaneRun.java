package io.github.libtmux;

import java.util.List;
import java.util.OptionalInt;

/**
 * How a command run in a pane with {@link Pane#run} ended, and what it printed.
 *
 * @param outcome why the wait ended
 * @param exitStatus the command's exit status, present once it finished and its end was read
 * @param output what the command printed, one element per line, without the plumbing around it.
 *     Lines tmux wrapped are rejoined
 * @param exact whether the output was cut out between both of its markers, so it is the command's
 *     and nothing else. False when the output outgrew the pane's history or the command cleared the
 *     screen, or when the command had not finished by the deadline
 */
public record PaneRun(Outcome outcome, OptionalInt exitStatus, List<String> output, boolean exact) {

    public PaneRun {
        output = List.copyOf(output);
    }

    /** Why a run's wait ended. */
    public enum Outcome {
        /** The command ended; its status is in {@link #exitStatus()}. */
        FINISHED,
        /** The deadline passed with the command still running. The output is what it had printed. */
        TIMED_OUT,
        /** The server went away underneath the command, so nothing about it can be relied on. */
        SERVER_GONE
    }

    /** Whether the command finished with status zero. */
    public boolean succeeded() {
        return outcome == Outcome.FINISHED && exitStatus.orElse(-1) == 0;
    }
}
