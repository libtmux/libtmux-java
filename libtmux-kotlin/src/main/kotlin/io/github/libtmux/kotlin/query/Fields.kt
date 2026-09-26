package io.github.libtmux.kotlin.query

import io.github.libtmux.query.FilterExpr
import io.github.libtmux.query.Fields
import java.util.regex.Pattern

/**
 * Kotlin-idiomatic wrappers over [Fields]' typed field handles, each holding the Java handle it
 * wraps. A value class, so this costs nothing beyond the Java object it already holds — the same
 * choice [io.github.libtmux.kotlin.Server]'s live handles reject, made here on purpose: a field
 * handle carries exactly one property, which is what makes the boxing tradeoff free.
 */
@JvmInline
public value class TextField<T : Any> internal constructor(private val java: Fields.TextField<T>) {
    public infix fun eq(value: String): FilterExpr<T> = java.`is`(value)
    public infix fun ne(value: String): FilterExpr<T> = java.isNot(value)
    public infix fun startsWith(value: String): FilterExpr<T> = java.startsWith(value)
    public infix fun endsWith(value: String): FilterExpr<T> = java.endsWith(value)
    public infix fun contains(value: String): FilterExpr<T> = java.contains(value)
    public infix fun matches(pattern: Pattern): FilterExpr<T> = java.matches(pattern)
    public infix fun oneOf(values: Collection<String>): FilterExpr<T> = java.`in`(values)
}

@JvmInline
public value class NumberField<T : Any> internal constructor(private val java: Fields.NumberField<T>) {
    public infix fun eq(value: Int): FilterExpr<T> = java.`is`(value)
    public infix fun ne(value: Int): FilterExpr<T> = java.isNot(value)
    public infix fun lessThan(value: Int): FilterExpr<T> = java.lessThan(value)
    public infix fun atMost(value: Int): FilterExpr<T> = java.atMost(value)
    public infix fun greaterThan(value: Int): FilterExpr<T> = java.greaterThan(value)
    public infix fun atLeast(value: Int): FilterExpr<T> = java.atLeast(value)
}

@JvmInline
public value class FlagField<T : Any> internal constructor(private val java: Fields.FlagField<T>) {
    public fun isTrue(): FilterExpr<T> = java.isTrue()
    public fun isFalse(): FilterExpr<T> = java.isFalse()
}

@JvmInline
public value class ToManyField<T : Any, R : Any> internal constructor(private val java: Fields.ToManyRef<T, R>) {
    public infix fun any(predicate: FilterExpr<R>): FilterExpr<T> = java.any(predicate)
    public infix fun all(predicate: FilterExpr<R>): FilterExpr<T> = java.all(predicate)
    public infix fun none(predicate: FilterExpr<R>): FilterExpr<T> = java.none(predicate)
}

@JvmInline
public value class ToOneField<T : Any, R : Any> internal constructor(private val java: Fields.ToOneRef<T, R>) {
    public infix fun matching(predicate: FilterExpr<R>): FilterExpr<T> = java.`is`(predicate)
}
