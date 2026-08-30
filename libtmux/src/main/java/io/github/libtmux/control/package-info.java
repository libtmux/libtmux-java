/**
 * A persistent tmux client that stays attached and answers one command at a time.
 *
 * <p>Control mode is what a semicolon group cannot be. Each request is independent, so a failure
 * does not discard the requests behind it, and each reply is framed with the request number that
 * produced it, so attribution is tmux's rather than something a client infers.
 *
 * <p>Deliberately not a {@link io.github.libtmux.transport.TmuxTransport}, and so not something
 * {@link io.github.libtmux.Server} can run over. That interface carries standard input, which
 * control mode has no per-command channel for and {@code Pane.paste} depends on; it offers a
 * request that stays blocked until another releases it, which would occupy the one request this
 * carrier has in flight; and it answers with an exit status and a separate error channel, which a
 * control reply does not have. An implementation would have to fail for those, which is worse than
 * not offering one. Use this for what it is better at: staying attached, and being told.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.control;

import org.jspecify.annotations.NullMarked;
