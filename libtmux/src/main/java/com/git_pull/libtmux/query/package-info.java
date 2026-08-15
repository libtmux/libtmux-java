/**
 * A filter expression that is both runnable and readable.
 *
 * <p>An expression extends {@link java.util.function.Predicate}, so it drops straight into a stream,
 * and it is a sealed tree of records, so the same value can be printed, serialized, or compiled into
 * a backend's own filter language. A lambda gives the first of those and none of the rest.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.query;

import org.jspecify.annotations.NullMarked;
