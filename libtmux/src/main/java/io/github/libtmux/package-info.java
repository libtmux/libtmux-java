/**
 * A typed Java client for tmux.
 *
 * <p>A {@link Session}, {@link Window} or {@link Pane} is an immutable capture of one moment, and a
 * method that changes tmux changes tmux rather than the handle. One rule decides what such a method
 * returns: <strong>a handle when it produces one the caller needs, and nothing otherwise.</strong> A
 * created window or pane is new, so {@code newWindow}, {@code split} and {@code breakOut} return it;
 * a renamed or retitled object is found again by its new label, so {@code rename} and {@code
 * retitle} return it under that label. Every other change — selecting, resizing, moving, sending —
 * returns nothing and leaves the handle describing the moment it was captured: call {@code
 * refresh()} for the state after.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux;

import org.jspecify.annotations.NullMarked;
