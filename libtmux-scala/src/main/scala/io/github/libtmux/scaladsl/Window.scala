package io.github.libtmux.scaladsl

import io.github.libtmux.{Window => JavaWindow}
import io.github.libtmux.scaladsl.query.Expr

/** One captured window placement, opaque over the Java handle. Equality
  * includes its session and index, since a window linked into more than one
  * session has more than one placement. Every operation beyond equality is an
  * extension, generated from the operation catalog or handwritten where the
  * catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  *
  * A pure read of the capture issues no command; every other operation asks tmux
  * now, blocking the calling thread.
  *
  * # What is on here
  *
  * The operations fall into a few groups:
  *
  * **Reading the capture.** [[Window.id]], [[Window.index]], [[Window.context]],
  * [[Window.name]], [[Window.active]], [[Window.linked]], [[Window.size]] and
  * [[Window.layout]] read what this capture stored about the window, and
  * [[Window.server]] and [[Window.session]] are the [[Server]] and [[Session]]
  * it sits in. The id names the underlying window and the index names this link,
  * so a window linked into two sessions has one id and two handles. A rename or
  * a resize after the capture does not show in it.
  *
  * [[Window.info]], [[Window.id]], [[Window.index]], [[Window.context]],
  * [[Window.name]], [[Window.active]], [[Window.linked]], [[Window.size]],
  * [[Window.layout]], [[Window.server]], [[Window.session]] and
  * [[Window.asJava]]
  *
  * **Panes.** [[Window.panes]] lists this link's panes in tmux's order as a
  * `Vector`, and [[Window.activePane]] is the one tmux had active, an `Option`;
  * both are pure reads. [[Window.split]] divides the active pane in half, from a
  * callback on a builder or a `SplitSpec`, and returns the pane that appeared.
  * It throws when the spec asks for something this server does not have.
  *
  * [[Window.panes]], [[Window.activePane]] and [[Window.split]]
  *
  * **Layout and size.** [[Window.selectLayout]] rearranges the panes into one of
  * tmux's built-in layouts, [[Window.nextLayout]] and [[Window.previousLayout]]
  * step to a neighbour, and [[Window.rotate]] rotates the panes. Read the
  * arrangement from [[Window.layout]] and hand it back to
  * [[Window.applyLayout]], which checks a string before tmux sees it.
  * [[Window.resizeTo]] takes terminal cells. [[Window.synchronizePanes]] copies
  * input typed into one pane into every pane of the window until
  * [[Window.stopSynchronizingPanes]].
  *
  * [[Window.selectLayout]], [[Window.nextLayout]], [[Window.previousLayout]],
  * [[Window.applyLayout]], [[Window.rotate]], [[Window.resizeTo]],
  * [[Window.synchronizePanes]] and [[Window.stopSynchronizingPanes]]
  *
  * **Moving and linking.** [[Window.select]] makes the window the active one in
  * its session. [[Window.linkTo]] links it into another session so one window
  * sits in both, [[Window.moveTo]] moves it to another session or to an exact
  * index there, and [[Window.unlink]] removes one link and throws when it is the
  * window's only one, which tmux refuses to remove.
  *
  * [[Window.select]], [[Window.linkTo]], [[Window.moveTo]] and [[Window.unlink]]
  *
  * **Changing the window.** [[Window.rename]] returns a replacement capture,
  * because this handle keeps its original state. [[Window.respawn]] kills what
  * is running in the window and starts it again, [[Window.displayPopup]] shows a
  * popup over it and needs an attached client, and [[Window.kill]] closes it.
  *
  * [[Window.rename]], [[Window.respawn]], [[Window.displayPopup]] and
  * [[Window.kill]]
  *
  * **Options and hooks.** [[Window.options]] and [[Window.hooks]] reach what is
  * set on this window alone, and every link to the window shares them.
  *
  * [[Window.options]] and [[Window.hooks]]
  *
  * **Formats and refreshing.** [[Window.expand]] expands a tmux format in this
  * window's context, and [[Window.refresh]] takes a new capture to see the
  * window as it is now. It leaves this handle unchanged and returns the new one,
  * and throws if the window is no longer linked here, from a server that still
  * answers.
  *
  * [[Window.expand]] and [[Window.refresh]]
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
