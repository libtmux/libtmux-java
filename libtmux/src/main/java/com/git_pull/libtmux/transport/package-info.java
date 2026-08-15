/**
 * Running one tmux command and getting its output back.
 *
 * <p>The transport is blocking and its caller may be a virtual thread. It owns every child process
 * it starts: no child outlives the call that started it, and a call that cannot say whether tmux
 * applied a command reports that uncertainty rather than inventing an exit status.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.transport;

import org.jspecify.annotations.NullMarked;
