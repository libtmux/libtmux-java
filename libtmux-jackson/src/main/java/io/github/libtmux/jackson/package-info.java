/**
 * Writing a filter expression down and reading it back.
 *
 * <p>Only exact field and relation handles declared by the supplied model can be written. A handle
 * may borrow a declared name while carrying a different accessor; refusing it is the difference
 * between a format and a hope.
 * Caller-owned models use namespaced ids so they cannot impersonate libtmux's built-in models.
 *
 * <p>The wire format carries its own schema version and stable model, field and operator ids. Java
 * class names and record component names are deliberately not wire identifiers, so the AST can be
 * refactored without breaking documents already written.
 *
 * <p>Optional: the core query model works without any of this on the classpath.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.jackson;

import org.jspecify.annotations.NullMarked;
