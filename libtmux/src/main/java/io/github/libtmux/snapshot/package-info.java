/**
 * A captured tmux hierarchy that answers questions without asking tmux anything.
 *
 * <p>Relations are keyed on the winlink — session, index and window together — because a window
 * linked into two sessions is one window and two positions. Keying on the window id alone would
 * merge them and lose an ordering tmux considers distinct.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.snapshot;

import org.jspecify.annotations.NullMarked;
