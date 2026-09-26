package io.github.libtmux.scaladsl.query

import io.github.libtmux.query.{FilterExpr => JavaFilterExpr}

/** A filter over `T`, both runnable and readable, opaque over Java's own
  * `FilterExpr[T]` — lossless by construction, the same reasoning
  * [[io.github.libtmux.scaladsl.Pane]] and its siblings rest on. `T` is the raw
  * Java handle type a Java field metamodel (`Pane_`, `Session_`, ...) is built
  * over, not this facade's opaque one: a query is a Java-shaped value that this
  * facade's own `.matching` extensions apply to captured Scala handles.
  */
opaque type Expr[T] = JavaFilterExpr[T]

object Expr {

  /** Wraps an existing Java expression, such as one a typed field method
    * already produced.
    */
  def apply[T](java: JavaFilterExpr[T]): Expr[T] = java

  // Nested, not top-level: extension-method implicit scope for Expr[X] only reaches Expr's own
  // companion (found regardless of the caller's package), never a plain top-level def in this
  // package — a caller outside io.github.libtmux.scaladsl.query, such as Pane.scala, would
  // otherwise need an explicit import for every one of these.
  extension [T](e: Expr[T]) {

    /** The underlying Java expression. Opaque wrapping is free, so this never
      * copies anything.
      */
    def asJava: JavaFilterExpr[T] = e

    def &&(other: Expr[T]): Expr[T] =
      (e: JavaFilterExpr[T]).and(other: JavaFilterExpr[T])
    def ||(other: Expr[T]): Expr[T] =
      (e: JavaFilterExpr[T]).or(other: JavaFilterExpr[T])
    def unary_! : Expr[T] = (e: JavaFilterExpr[T]).negate()

    /** `&&`, spelled out. */
    def and(other: Expr[T]): Expr[T] = e && other

    /** `||`, spelled out. */
    def or(other: Expr[T]): Expr[T] = e || other

    /** `unary_!`, spelled out. */
    def not: Expr[T] = !e

    /** Renders this expression as inspectable text. */
    def describe: String = (e: JavaFilterExpr[T]).describe()

    /** Evaluates this expression against one value, with no tmux I/O. */
    def matches(value: T): Boolean = (e: JavaFilterExpr[T]).test(value)
  }
}
