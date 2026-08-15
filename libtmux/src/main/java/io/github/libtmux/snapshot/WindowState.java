package io.github.libtmux.snapshot;

import io.github.libtmux.Dimensions;

/**
 * A window as one capture saw it, at one of its positions.
 *
 * @param context which session and index this link occupies
 * @param name the window name
 * @param active whether it was the active window of its session
 * @param panes how many panes it contained
 * @param linked whether the window is linked into more than one session
 * @param size how large the window was, in terminal cells
 * @param layout tmux's own serialized layout, which can be handed back to select-layout
 */
public record WindowState(
        WindowContext context,
        String name,
        boolean active,
        int panes,
        boolean linked,
        Dimensions size,
        String layout) {}
