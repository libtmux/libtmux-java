package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Client => JavaClient,
  Pane => JavaPane,
  Session => JavaSession,
  Window => JavaWindow
}
import io.github.libtmux.query.{FilterExpr, Selections}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** Strict cardinality and opt-in Java expressions over captured handles.
  * Built-in Java fields read captures; custom accessors retain their own
  * effects.
  */
object Queries {

  /** Returns one value; absence and ambiguity retain Java's distinct
    * exceptions.
    */
  def exactlyOne[A](values: Iterable[A]): A =
    Selections.exactlyOne(values.asJava)

  /** Returns None for absence and rejects ambiguity with
    * MultipleMatchesException.
    */
  def oneOrNone[A](values: Iterable[A]): Option[A] =
    Selections.oneOrEmpty(values.asJava).toScala

  /** Evaluates on each captured pane's Java handle. */
  def panes(expression: FilterExpr[JavaPane]): blocking.Pane => Boolean =
    pane => expression.test(pane.asJava)

  /** Evaluates on each captured window placement's Java handle. */
  def windows(expression: FilterExpr[JavaWindow]): blocking.Window => Boolean =
    window => expression.test(window.asJava)

  /** Evaluates on each captured session's Java handle. */
  def sessions(
      expression: FilterExpr[JavaSession]
  ): blocking.Session => Boolean =
    session => expression.test(session.asJava)

  /** Evaluates on each captured client's Java handle. */
  def clients(expression: FilterExpr[JavaClient]): blocking.Client => Boolean =
    client => expression.test(client.asJava)
}
