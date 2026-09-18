package io.github.libtmux.snapshot;

import io.github.libtmux.Dimensions;
import io.github.libtmux.PaneEdges;
import io.github.libtmux.PaneId;
import io.github.libtmux.PanePosition;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A pane as one capture saw it.
 *
 * @param context the winlink the pane was reached through
 * @param id the pane's stable id
 * @param index the pane's position, which shifts as neighbours come and go
 * @param active whether it was the active pane of its window
 * @param currentCommand the command tmux reported running in it
 * @param size how large the pane was, in terminal cells
 * @param position where the pane's top-left corner sat in its window, in terminal cells
 * @param title the pane title, which a program inside it can change
 * @param currentPath the working directory tmux reported for it, as text. Not a {@link
 *     java.nio.file.Path}: a directory name is bytes to tmux, and converting one this JVM's
 *     encoding cannot represent throws — which, in a capture, would lose every other pane over one
 *     pane's directory. {@code Pane.currentPath()} converts on request instead
 * @param pid the process id of the program running in it, or empty when tmux reports no process at
 *     all — never a literal zero, so a caller cannot mistake the sentinel for a real pid
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
        PanePosition position,
        String title,
        String currentPath,
        OptionalLong pid,
        PaneEdges edges,
        Optional<Boolean> floating) {}
