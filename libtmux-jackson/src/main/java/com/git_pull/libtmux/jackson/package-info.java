/**
 * Writing a filter expression down and reading it back.
 *
 * <p>Only expressions built from a metamodel can be written. A field built from a lambda has a
 * caller-chosen name and an accessor nobody else can resolve, so it has no wire identity; refusing
 * it is the difference between a format and a hope.
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
package com.git_pull.libtmux.jackson;

import org.jspecify.annotations.NullMarked;
