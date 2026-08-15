package io.github.libtmux.control;

import io.github.libtmux.batch.OperationOutcome;
import java.util.List;

/**
 * One command's reply in control mode.
 *
 * @param outcome what tmux said became of it; never {@code SKIPPED}, because control-mode requests
 *     are independent and a failure discards nothing behind it
 * @param lines the lines tmux produced between the reply's own begin and end markers
 */
public record ControlReply(OperationOutcome outcome, List<String> lines) {

    public ControlReply {
        lines = List.copyOf(lines);
    }

    /** Whether tmux ran the command and it succeeded. */
    public boolean succeeded() {
        return outcome == OperationOutcome.COMPLETE;
    }

    /** Counts only: reply lines carry pane content. */
    @Override
    public String toString() {
        return "ControlReply[" + outcome + ", lines=" + lines.size() + "]";
    }
}
