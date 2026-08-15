package io.github.libtmux;

import java.util.List;

/**
 * What runs in a new pane.
 *
 * <p>One choice rather than a command field beside an emptiness flag. tmux refuses the pair —
 * {@code command cannot be given for empty pane} — and refuses it before spawning anything, so the
 * combination is not a state the server will ever be in. Sealing it means no value can carry both.
 */
public sealed interface PaneStart {

    /** Whatever the session's {@code default-command} or shell is. */
    record Shell() implements PaneStart {}

    /**
     * A command, which closes the pane when it exits unless the pane is kept.
     *
     * @param argv the command and its arguments
     */
    record Command(List<String> argv) implements PaneStart {
        public Command {
            argv = List.copyOf(argv);
            if (argv.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
        }
    }

    /** No process at all. Requires tmux 3.7. */
    record Empty() implements PaneStart {}

    /** Whatever the session's shell is. */
    static PaneStart shell() {
        return new Shell();
    }

    /** A command, which closes the pane when it exits unless the pane is kept. */
    static PaneStart command(String... argv) {
        return new Command(List.of(argv));
    }

    /** No process at all. Requires tmux 3.7. */
    static PaneStart empty() {
        return new Empty();
    }
}
