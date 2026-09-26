package io.github.libtmux.scaladsl

import io.github.libtmux.{Window => JavaWindow}
import io.github.libtmux.scaladsl.query.Expr

/** One captured window placement, opaque over the Java handle. Equality
  * includes its session and index, since a window linked into more than one
  * session has more than one placement. Every operation beyond equality is an
  * extension, generated from the operation catalog or handwritten where the
  * catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  */
opaque type Window = JavaWindow

object Window {

  private[scaladsl] def wrap(java: JavaWindow): Window = java

  given CanEqual[Window, Window] = CanEqual.derived

  // Generated from field-catalog.tsv: see Pane.scala's own export for why this is a re-export of
  // a separate generated object rather than a same-file companion.
  export io.github.libtmux.scaladsl.generated.WindowFields.*

  // Nested, not top-level: see Pane.scala's own asJava for why.
  extension (self: Window) {

    /** The underlying Java window link. Opaque wrapping is free, so this never
      * copies scope or state.
      */
    def asJava: JavaWindow = self
  }

  extension (windows: Vector[Window]) {

    /** The captured window placements `expr` matches: a local filter over what
      * is already held.
      */
    def matching(expr: Expr[JavaWindow]): Vector[Window] =
      windows.filter(window => expr.matches(window.asJava))
  }
}
