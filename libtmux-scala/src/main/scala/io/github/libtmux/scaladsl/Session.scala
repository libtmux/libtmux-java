package io.github.libtmux.scaladsl

import io.github.libtmux.{Session => JavaSession}
import io.github.libtmux.scaladsl.query.Expr

/** A captured session, opaque over the Java handle; every operation beyond
  * equality is an extension, generated from the operation catalog or
  * handwritten where the catalog marks it `WAIT`, `STREAM` or `LIFECYCLE`.
  *
  * A pure read of the capture issues no command; every other operation asks tmux
  * now, blocking the calling thread.
  *
  * # What is on here
  *
  * The operations fall into a few groups:
  *
  * **Reading the capture.** [[Session.id]], [[Session.name]] and
  * [[Session.attached]] read what this capture stored about the session, and
  * [[Session.server]] is the [[Server]] it lives on. The id is stable, the name
  * is state a user may change at any time, and a rename or an attach after the
  * capture does not show in it.
  *
  * [[Session.info]], [[Session.id]], [[Session.name]], [[Session.attached]],
  * [[Session.server]] and [[Session.asJava]]
  *
  * **Windows and panes.** [[Session.windows]] lists the session's windows in
  * tmux's order as a `Vector`, and [[Session.activeWindow]] and
  * [[Session.activePane]] are the window tmux had active and the active pane of
  * that window, each an `Option`. All three are pure reads.
  * [[Session.selectWindow]] makes a window the active one,
  * [[Session.nextWindow]] and [[Session.previousWindow]] step to a neighbour
  * with wrap-around, and [[Session.lastWindow]] goes back to the one that was
  * active before.
  *
  * [[Session.windows]], [[Session.activeWindow]], [[Session.activePane]],
  * [[Session.selectWindow]], [[Session.nextWindow]], [[Session.previousWindow]]
  * and [[Session.lastWindow]]
  *
  * **Changing the session.** [[Session.newWindow]] creates a window from a name,
  * a callback on a builder, or a `WindowSpec`, and returns the window tmux made.
  * [[Session.rename]] returns a replacement capture, because this handle keeps
  * its original state. [[Session.setHistoryLimit]] sets the scrollback retained
  * by panes created in this session afterwards. [[Session.detachClients]] leaves
  * the session running, and [[Session.kill]] takes every window with it.
  *
  * [[Session.newWindow]], [[Session.rename]], [[Session.setHistoryLimit]],
  * [[Session.detachClients]] and [[Session.kill]]
  *
  * **Options, environment, and hooks.** These reach what is set on this session
  * alone: its own options, its own environment, which a process started in it is
  * given on top of the server's, and its own hooks. The [[Server]] holds the
  * global ones, which a session inherits.
  *
  * [[Session.options]], [[Session.environment]] and [[Session.hooks]]
  *
  * **Formats and refreshing.** [[Session.expand]] expands a tmux format in this
  * session's context, and [[Session.refresh]] takes a new capture to see the
  * session as it is now. It leaves this handle unchanged and returns the new
  * one, and throws if the session is gone from a server that still answers.
  *
  * [[Session.expand]] and [[Session.refresh]]
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
