package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.batch.BatchResult;
import java.util.List;

/**
 * A sequence of tmux commands where each one acts on what the last one made.
 *
 * <p>tmux moves its own current target as a group runs: a {@code new-window} makes the new window
 * current, a following {@code split-window} splits that window, and a following {@code send-keys}
 * types into the pane that split produced. None of these steps names a target, and that is the
 * point — the alternative is a round trip per step to learn the id of the thing just created.
 *
 * <p>The whole chain is one tmux invocation, and every step is attributed individually, so a chain
 * that fails halfway says which step failed and which never ran.
 */
public final class CommandChain {

    private final Batch batch;

    CommandChain(Batch batch) {
        this.batch = batch;
    }

    /** Creates a window and makes it the one following steps act on. */
    public CommandChain newWindow(String name) {
        return then("new-window", "-n", name);
    }

    /** Renames the current window. */
    public CommandChain renameWindow(String name) {
        return then("rename-window", name);
    }

    /**
     * Splits the current pane into a left and a right one.
     *
     * <p>Named for what it produces rather than for tmux's {@code -h}, which reads as though it
     * described the divider.
     */
    public CommandChain splitLeftRight() {
        return then("split-window", "-h");
    }

    /** Splits the current pane into a top and a bottom one. */
    public CommandChain splitTopBottom() {
        return then("split-window", "-v");
    }

    /** Types a line into the current pane and presses Enter, which is how a command gets run. */
    public CommandChain sendLine(String command) {
        return then("send-keys", command, "Enter");
    }

    /**
     * Arranges the current window.
     *
     * @throws IllegalArgumentException if tmux would not recognise the layout, which on some
     *     versions ends the whole server rather than the command
     */
    public CommandChain arrange(String layout) {
        return then("select-layout", Layouts.require(layout));
    }

    /** Adds any tmux command, for whatever this class does not name. */
    public CommandChain then(String... argv) {
        batch.add(argv);
        return this;
    }

    /** Adds any tmux command. */
    public CommandChain then(List<String> argv) {
        batch.add(argv);
        return this;
    }

    /** Runs the whole chain in one tmux invocation, attributing each step. */
    public BatchResult run() {
        return batch.run();
    }
}
