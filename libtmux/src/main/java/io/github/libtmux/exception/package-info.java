/**
 * Every failure this library raises, as one sealed tree.
 *
 * <p>{@link io.github.libtmux.exception.LibTmuxException} is sealed, and so is each of its branches,
 * so a {@code switch} in Java, a {@code when} in Kotlin or a {@code match} in Scala over the permitted
 * subtypes is checked for exhaustiveness: a new kind of failure breaks the build of every caller that
 * has to decide what to do about it. Each subtype is named for what the caller does next.
 *
 * <p>Programmer error stays outside the tree: a null argument is a {@link NullPointerException}, an
 * invalid value an {@link IllegalArgumentException}, and a handle used after its server was closed a
 * {@link io.github.libtmux.exception.ServerClosedException}.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.exception;

import org.jspecify.annotations.NullMarked;
