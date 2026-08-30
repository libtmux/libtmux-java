/**
 * Running one tmux command and getting its output back.
 *
 * <p>The transport is blocking and its caller may be a virtual thread. Cleanup owns the command
 * process and descendants still visible when cleanup begins; incomplete reclamation is reported. A
 * tmux server that has already daemonized is intentionally outside that snapshot. A call that
 * cannot say whether tmux applied a command reports that uncertainty rather than inventing an exit
 * status.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.transport;

import org.jspecify.annotations.NullMarked;
