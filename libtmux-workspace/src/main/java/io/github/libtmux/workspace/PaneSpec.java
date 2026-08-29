package io.github.libtmux.workspace;

import java.util.List;

/**
 * One pane and what to run in it.
 *
 * @param commands the commands to send, in order; a pane with none is left at its shell
 */
public record PaneSpec(List<String> commands) {

    public PaneSpec {
        commands = List.copyOf(commands);
        for (int index = 0; index < commands.size(); index++) {
            if (commands.get(index).indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "pane command " + index + " contains NUL, which no process can carry");
            }
        }
    }
}
