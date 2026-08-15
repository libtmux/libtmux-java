/**
 * Building tmux format templates and reading the rows they produce.
 *
 * <p>tmux answers a listing as lines of text, so the only thing separating one field from the next
 * is a string the client chose. Anything a user can put in a window name can appear in that text,
 * which makes the choice of separator a correctness question rather than a formatting one.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.format;

import org.jspecify.annotations.NullMarked;
