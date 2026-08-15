package io.github.libtmux.kotlin

import io.github.libtmux.query.FilterExpr

/**
 * Inverts an expression, so `!Pane_.active().isTrue()` says what it means.
 *
 * There is no matching `and` or `or` here, deliberately. Those are already methods on
 * [FilterExpr], and an extension of the same name would be shadowed by the member inside its own
 * body while winning at an infix call site — correct either way, but for a reason no reader should
 * have to work out. `a.and(b)` is one character longer than `a and b` and always means the thing it
 * appears to.
 *
 * `negate` rather than `not` on the Java side because [java.util.function.Predicate] already owns
 * `not`, and these expressions are predicates.
 *
 * The bound is `T : Any` rather than `T`, and that is not a style choice. The core is `@NullMarked`,
 * so Kotlin reads `FilterExpr` as `FilterExpr<T : Any>`; an unbounded `T` here would admit a
 * nullable entity the core says cannot exist. Compiled with `-Xjspecify-annotations=strict`, so it
 * is an error rather than a warning — this file did not compile until the bound was written.
 */
public operator fun <T : Any> FilterExpr<T>.not(): FilterExpr<T> = negate()
