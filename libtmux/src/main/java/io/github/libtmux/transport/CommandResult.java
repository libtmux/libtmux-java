package io.github.libtmux.transport;

import java.util.List;

/**
 * One completed invocation.
 *
 * <p>A nonzero exit is data, not a failure. tmux reports an ordinary "no such pane" the same way it
 * reports a real problem, so only a higher-level method knows which one should raise.
 *
 * @param exitCode the process exit status
 * @param stdout stdout split into lines, keeping interior blanks
 * @param stderr stderr split into lines, with blanks dropped
 */
public record CommandResult(int exitCode, List<String> stdout, List<String> stderr) {

    public CommandResult {
        stdout = List.copyOf(stdout);
        stderr = List.copyOf(stderr);
    }

    /** Whether tmux reported success. */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Counts only, because both channels carry captured terminal content. */
    @Override
    public String toString() {
        return "CommandResult[exitCode=" + exitCode + ", stdoutLines=" + stdout.size() + ", stderrLines="
                + stderr.size() + "]";
    }
}
