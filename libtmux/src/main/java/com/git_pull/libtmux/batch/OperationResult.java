package com.git_pull.libtmux.batch;

import java.util.List;

/**
 * One operation's own result within a batch.
 *
 * @param argv the operation as it was submitted
 * @param outcome what became of it
 * @param stdout the lines this operation produced, separated from its neighbours'
 * @param stderr the error text, which tmux emits without saying which operation produced it, so it
 *     is attached to the one that failed
 */
public record OperationResult(List<String> argv, OperationOutcome outcome, List<String> stdout, List<String> stderr) {

    public OperationResult {
        argv = List.copyOf(argv);
        stdout = List.copyOf(stdout);
        stderr = List.copyOf(stderr);
    }

    /** Whether tmux ran this operation and it succeeded. */
    public boolean succeeded() {
        return outcome == OperationOutcome.COMPLETE;
    }

    /** Counts only: argv and output carry pane content and socket paths. */
    @Override
    public String toString() {
        return "OperationResult[" + outcome + ", argumentCount=" + argv.size() + ", stdoutLines=" + stdout.size() + "]";
    }
}
