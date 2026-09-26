package io.github.libtmux.scaladsl.query

import io.github.libtmux.{query => javaquery}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters._

/** Typed field handles, wrapping Java's `io.github.libtmux.query.Fields`
  * records so every operator returns this facade's [[Expr]] rather than a raw
  * Java `FilterExpr`. An extension cannot reshape these methods' return type on
  * the Java records directly — same-named would silently lose to the Java
  * member, exactly the trap `-Werror` exists to catch — so each kind gets its
  * own small Scala class instead, one line per operator, forwarding to the Java
  * one underneath.
  */
object Fields {

  final class TextField[T] private[scaladsl] (
      private val java: javaquery.Fields.TextField[T]
  ) {
    def is(value: String): Expr[T] = Expr(java.is(value))
    def isNot(value: String): Expr[T] = Expr(java.isNot(value))
    def contains(value: String): Expr[T] = Expr(java.contains(value))
    def startsWith(value: String): Expr[T] = Expr(java.startsWith(value))
    def endsWith(value: String): Expr[T] = Expr(java.endsWith(value))
    def matches(value: Pattern): Expr[T] = Expr(java.matches(value))
    def in(values: Iterable[String]): Expr[T] = Expr(
      java.in(values.asJavaCollection)
    )
  }

  final class NumberField[T] private[scaladsl] (
      private val java: javaquery.Fields.NumberField[T]
  ) {
    def is(value: Int): Expr[T] = Expr(java.is(value))
    def isNot(value: Int): Expr[T] = Expr(java.isNot(value))
    def lessThan(value: Int): Expr[T] = Expr(java.lessThan(value))
    def atMost(value: Int): Expr[T] = Expr(java.atMost(value))
    def greaterThan(value: Int): Expr[T] = Expr(java.greaterThan(value))
    def atLeast(value: Int): Expr[T] = Expr(java.atLeast(value))
  }

  final class FlagField[T] private[scaladsl] (
      private val java: javaquery.Fields.FlagField[T]
  ) {
    def is(value: Boolean): Expr[T] = Expr(java.is(value))
    def isNot(value: Boolean): Expr[T] = Expr(java.isNot(value))
    def isTrue: Expr[T] = Expr(java.isTrue())
    def isFalse: Expr[T] = Expr(java.isFalse())
  }

  final class ToOneRef[T, R] private[scaladsl] (
      private val java: javaquery.Fields.ToOneRef[T, R]
  ) {
    def is(predicate: Expr[R]): Expr[T] = Expr(java.is(predicate.asJava))
  }

  final class ToManyRef[T, R] private[scaladsl] (
      private val java: javaquery.Fields.ToManyRef[T, R]
  ) {
    def any(predicate: Expr[R]): Expr[T] = Expr(java.any(predicate.asJava))
    def all(predicate: Expr[R]): Expr[T] = Expr(java.all(predicate.asJava))
    def none(predicate: Expr[R]): Expr[T] = Expr(java.none(predicate.asJava))
  }
}
