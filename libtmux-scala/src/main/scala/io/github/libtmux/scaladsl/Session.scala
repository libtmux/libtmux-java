package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Session => JavaSession,
  Session_,
  Window => JavaWindow
}
import io.github.libtmux.scaladsl.query.{Expr, Fields}

/** A captured session, opaque over the Java handle; every operation beyond
  * equality is an extension, generated from the operation catalog or
  * handwritten where the catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  */
opaque type Session = JavaSession

object Session {

  private[scaladsl] def wrap(java: JavaSession): Session = java

  given CanEqual[Session, Session] = CanEqual.derived

  def id: Fields.TextField[JavaSession] = new Fields.TextField(Session_.id())
  def name: Fields.TextField[JavaSession] =
    new Fields.TextField(Session_.name())
  def attached: Fields.FlagField[JavaSession] =
    new Fields.FlagField(Session_.attached())
  def windowCount: Fields.NumberField[JavaSession] =
    new Fields.NumberField(Session_.windowCount())
  def windows: Fields.ToManyRef[JavaSession, JavaWindow] =
    new Fields.ToManyRef(Session_.windows())

  // Nested, not top-level: see Pane.scala's own asJava for why.
  extension (self: Session) {

    /** The underlying Java session. Opaque wrapping is free, so this never
      * copies scope or state.
      */
    def asJava: JavaSession = self
  }

  extension (sessions: Vector[Session]) {

    /** The captured sessions `expr` matches: a local filter, evaluated against
      * what is already held.
      */
    def matching(expr: Expr[JavaSession]): Vector[Session] =
      sessions.filter(session => expr.matches(session.asJava))
  }
}
