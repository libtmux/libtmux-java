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

/**
 * Selects the elements an expression matches.
 *
 * Kotlin's own `filter` takes a function, not a [java.util.function.Predicate], so handing it a
 * [FilterExpr] does not compile — the one place where reading the Java documentation and writing
 * Kotlin part company. This is that overload, so `panes.filter(Pane_.active().isTrue())` means what
 * it appears to.
 *
 * The list is already in hand: this asks tmux nothing.
 */
public fun <T : Any> Iterable<T>.filter(expression: FilterExpr<T>): List<T> = filter(expression::test)
