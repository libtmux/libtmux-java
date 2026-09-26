package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Pane => JavaPane,
  Session => JavaSession,
  Window => JavaWindow,
  Window_
}
import io.github.libtmux.scaladsl.query.{Expr, Fields}

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

  def id: Fields.TextField[JavaWindow] = new Fields.TextField(Window_.id())
  def name: Fields.TextField[JavaWindow] = new Fields.TextField(Window_.name())
  def index: Fields.NumberField[JavaWindow] =
    new Fields.NumberField(Window_.index())
  def active: Fields.FlagField[JavaWindow] =
    new Fields.FlagField(Window_.active())
  def linked: Fields.FlagField[JavaWindow] =
    new Fields.FlagField(Window_.linked())
  def width: Fields.NumberField[JavaWindow] =
    new Fields.NumberField(Window_.width())
  def height: Fields.NumberField[JavaWindow] =
    new Fields.NumberField(Window_.height())
  def paneCount: Fields.NumberField[JavaWindow] =
    new Fields.NumberField(Window_.paneCount())
  def panes: Fields.ToManyRef[JavaWindow, JavaPane] =
    new Fields.ToManyRef(Window_.panes())
  def session: Fields.ToOneRef[JavaWindow, JavaSession] =
    new Fields.ToOneRef(Window_.session())

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
