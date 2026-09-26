package io.github.libtmux.scaladsl

import io.github.libtmux.{Session => JavaSession}
import io.github.libtmux.scaladsl.query.Expr

/** A captured session, opaque over the Java handle; every operation beyond
  * equality is an extension, generated from the operation catalog or
  * handwritten where the catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  */
opaque type Session = JavaSession

object Session {

  private[scaladsl] def wrap(java: JavaSession): Session = java

  given CanEqual[Session, Session] = CanEqual.derived

  // Generated from field-catalog.tsv: see Pane.scala's own export for why this is a re-export of
  // a separate generated object rather than a same-file companion.
  export io.github.libtmux.scaladsl.generated.SessionFields.*

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
