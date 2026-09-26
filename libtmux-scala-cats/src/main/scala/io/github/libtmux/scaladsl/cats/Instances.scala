package io.github.libtmux.scaladsl.cats

import _root_.cats.{Eq, Hash, Order, Show}
import io.github.libtmux.{PaneId, SessionId, TmuxVersion, WindowId}
import io.github.libtmux.scaladsl as direct

/** Instances derived from Java's `equals`/`hashCode`/`toString`, and from
  * `TmuxVersion`'s own `Comparable` — the one handle-adjacent type that
  * actually has a natural order. None of `Server`, `Session`, `Window`, `Pane`
  * or `Client` implements `Comparable` in Java, and none has a
  * domain-meaningful order (a pane's tmux index is not identity, and two panes
  * can share an index across different windows), so those get
  * `Eq`/`Show`/`Hash` only — never a manufactured `Order` that invites
  * `.sorted`/`SortedSet` producing an ordering nobody asked for.
  */
object Instances {

  /** `.equals`, not `==`: this runs for an unconstrained `A`, with no
    * `CanEqual[A, A]` in scope, and it is exactly the "trust me, this pair is
    * comparable" assertion `CanEqual.derived` makes for `==` itself — `.equals`
    * is the one operation `strictEquality` never restricts.
    */
  private def derived[A]: Eq[A] & Hash[A] & Show[A] = new Eq[A]
    with Hash[A]
    with Show[A] {
    def eqv(x: A, y: A): Boolean = x.equals(y)
    def hash(x: A): Int = x.hashCode()
    def show(x: A): String = x.toString
  }

  given serverInstances
      : (Eq[direct.Server] & Hash[direct.Server] & Show[direct.Server]) =
    derived
  given directSessionInstances
      : (Eq[direct.Session] & Hash[direct.Session] & Show[direct.Session]) =
    derived
  given directWindowInstances
      : (Eq[direct.Window] & Hash[direct.Window] & Show[direct.Window]) =
    derived
  given directPaneInstances
      : (Eq[direct.Pane] & Hash[direct.Pane] & Show[direct.Pane]) = derived
  given directClientInstances
      : (Eq[direct.Client] & Hash[direct.Client] & Show[direct.Client]) =
    derived

  given paneIdInstances: (Eq[PaneId] & Hash[PaneId] & Show[PaneId]) = derived
  given sessionIdInstances
      : (Eq[SessionId] & Hash[SessionId] & Show[SessionId]) = derived
  given windowIdInstances: (Eq[WindowId] & Hash[WindowId] & Show[WindowId]) =
    derived

  /** `TmuxVersion` already defines a natural order in Java (`implements
    * Comparable`); this is a direct projection of it, not a manufactured one.
    */
  given tmuxVersionInstances: (Order[TmuxVersion] & Show[TmuxVersion]) =
    new Order[TmuxVersion] with Show[TmuxVersion] {
      def compare(x: TmuxVersion, y: TmuxVersion): Int = x.compareTo(y)
      def show(x: TmuxVersion): String = x.toString
    }

  given sessionInstances[F[_]]
      : (Eq[Session[F]] & Hash[Session[F]] & Show[Session[F]]) =
    new Eq[Session[F]] with Hash[Session[F]] with Show[Session[F]] {
      def eqv(x: Session[F], y: Session[F]): Boolean =
        x.underlying == y.underlying
      def hash(x: Session[F]): Int = x.underlying.hashCode()
      def show(x: Session[F]): String = x.underlying.toString
    }

  given windowInstances[F[_]]
      : (Eq[Window[F]] & Hash[Window[F]] & Show[Window[F]]) =
    new Eq[Window[F]] with Hash[Window[F]] with Show[Window[F]] {
      def eqv(x: Window[F], y: Window[F]): Boolean =
        x.underlying == y.underlying
      def hash(x: Window[F]): Int = x.underlying.hashCode()
      def show(x: Window[F]): String = x.underlying.toString
    }

  given paneInstances[F[_]]: (Eq[Pane[F]] & Hash[Pane[F]] & Show[Pane[F]]) =
    new Eq[Pane[F]] with Hash[Pane[F]] with Show[Pane[F]] {
      def eqv(x: Pane[F], y: Pane[F]): Boolean = x.underlying == y.underlying
      def hash(x: Pane[F]): Int = x.underlying.hashCode()
      def show(x: Pane[F]): String = x.underlying.toString
    }

  given clientInstances[F[_]]
      : (Eq[Client[F]] & Hash[Client[F]] & Show[Client[F]]) =
    new Eq[Client[F]] with Hash[Client[F]] with Show[Client[F]] {
      def eqv(x: Client[F], y: Client[F]): Boolean =
        x.underlying == y.underlying
      def hash(x: Client[F]): Int = x.underlying.hashCode()
      def show(x: Client[F]): String = x.underlying.toString
    }
}
