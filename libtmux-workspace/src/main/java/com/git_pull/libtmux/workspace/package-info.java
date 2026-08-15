/**
 * Building a tmux session from a written description.
 *
 * <p>Reads the shape of a tmuxp workspace file — a session, its windows, their panes and the
 * commands to run in them — and applies it to a server. Runtime compatibility with tmuxp is not the
 * goal; being able to start from a file somebody already has is.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.workspace;

import org.jspecify.annotations.NullMarked;
