/**
 * A persistent tmux client that stays attached and answers one command at a time.
 *
 * <p>Control mode is what a semicolon group cannot be. Each request is independent, so a failure
 * does not discard the requests behind it, and each reply is framed with the request number that
 * produced it, so attribution is tmux's rather than something a client infers.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.control;

import org.jspecify.annotations.NullMarked;
