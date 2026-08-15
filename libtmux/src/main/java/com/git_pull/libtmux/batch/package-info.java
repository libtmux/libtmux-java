/**
 * Running several tmux commands in one invocation, and knowing what happened to each.
 *
 * <p>tmux runs a group of commands until one fails and then discards the rest, so the number of
 * replies does not identify which command failed. Every operation therefore gets an outcome of its
 * own rather than the batch getting a single exit status.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.batch;

import org.jspecify.annotations.NullMarked;
