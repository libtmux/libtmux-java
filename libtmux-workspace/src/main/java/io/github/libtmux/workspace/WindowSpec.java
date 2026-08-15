package io.github.libtmux.workspace;

import java.util.List;
import java.util.Optional;

/**
 * One window, its panes, and how they are arranged.
 *
 * @param name the window name
 * @param layout the tmux layout to apply once the panes exist, if the file asked for one
 * @param panes the panes to create, in order; a window always has at least one
 */
public record WindowSpec(String name, Optional<String> layout, List<PaneSpec> panes) {

    public WindowSpec {
        panes = List.copyOf(panes);
        if (panes.isEmpty()) {
            throw new IllegalArgumentException("window '" + name + "' has no panes");
        }
    }
}
