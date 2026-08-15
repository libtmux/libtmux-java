package io.github.libtmux.snapshot;

import io.github.libtmux.Dimensions;
import io.github.libtmux.PaneEdges;
import io.github.libtmux.PaneId;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A pane as one capture saw it.
 *
 * @param context the winlink the pane was reached through
 * @param id the pane's stable id
 * @param index the pane's position, which shifts as neighbours come and go
 * @param active whether it was the active pane of its window
 * @param currentCommand the command tmux reported running in it
 * @param size how large the pane was, in terminal cells
 * @param title the pane title, which a program inside it can change
 * @param currentPath the working directory tmux reported for it
 * @param pid the process id of the program running in it
 * @param edges which sides of its window the pane touches
 * @param floating whether the pane floats, or empty when the running tmux cannot say. Before 3.7
 *     the format expands to nothing at all, and reporting that as {@code false} would be
 *     indistinguishable from a tmux that looked and found the pane was not floating.
 */
public record PaneState(
        WindowContext context,
        PaneId id,
        int index,
        boolean active,
        String currentCommand,
        Dimensions size,
        String title,
        Path currentPath,
        long pid,
        PaneEdges edges,
        Optional<Boolean> floating) {}
