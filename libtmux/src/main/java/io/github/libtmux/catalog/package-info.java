/**
 * Machine-readable classification of the core API's tmux operations.
 *
 * <p>{@link io.github.libtmux.catalog.Operation} marks a public method with the {@link
 * io.github.libtmux.catalog.Kind} of tmux operation it performs or describes, so the Kotlin and
 * Scala facades, and generated reference docs, are built from one classification rather than each
 * inferring it from names and signatures. {@link io.github.libtmux.catalog.Advanced} marks a type
 * or method that is public for cross-process orchestration and is not part of the surface a
 * facade mirrors.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.catalog;

import org.jspecify.annotations.NullMarked;
