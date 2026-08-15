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
    }
}
