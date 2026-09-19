/**
 * Building a tmux session from a written description.
 *
 * <p>Reads the shape of a tmuxp workspace file — a session, its windows, their panes and the
 * commands to run in them — and applies it to a server. Runtime compatibility with tmuxp is not the
 * goal; being able to start from a file somebody already has is.
 *
 * <p>This is not what the {@code tmux-workspace} command runs. The two are separate
 * implementations and read different document languages: everything past a session name, windows,
 * panes and their commands — start directories, environment, options, scripts, window indexes,
 * focus, imports and the tmuxp bridge — belongs to the command, along with the behaviour around
 * building: the temporary window, index reservation, pane readiness, attaching and appending, and
 * removing a session it could not finish. A file this package accepts is a file the command also
 * accepts; the reverse does not hold, and neither does the behaviour.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.workspace;

import org.jspecify.annotations.NullMarked;
