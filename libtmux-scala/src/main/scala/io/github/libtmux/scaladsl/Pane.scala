package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Pane => JavaPane,
  PaneRun,
  Pane_,
  TextOutcome,
  WakeReason
}
import io.github.libtmux.scaladsl.query.{Expr, Fields}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

/** A captured pane occurrence, opaque over the Java handle. Java equality
  * identifies the physical pane; every operation beyond that is an extension,
  * generated from the operation catalog or handwritten where the catalog marks
  * it `WAIT`, `STREAM` or `LIFECYCLE`.
  */
opaque type Pane = JavaPane

object Pane {

  private[scaladsl] def wrap(java: JavaPane): Pane = java

  given CanEqual[Pane, Pane] = CanEqual.derived

  def id: Fields.TextField[JavaPane] = new Fields.TextField(Pane_.id())
  def command: Fields.TextField[JavaPane] =
    new Fields.TextField(Pane_.command())
  def index: Fields.NumberField[JavaPane] =
    new Fields.NumberField(Pane_.index())
  def active: Fields.FlagField[JavaPane] = new Fields.FlagField(Pane_.active())
  def title: Fields.TextField[JavaPane] = new Fields.TextField(Pane_.title())
  def path: Fields.TextField[JavaPane] = new Fields.TextField(Pane_.path())
  def width: Fields.NumberField[JavaPane] =
    new Fields.NumberField(Pane_.width())
  def height: Fields.NumberField[JavaPane] =
    new Fields.NumberField(Pane_.height())
  def left: Fields.NumberField[JavaPane] = new Fields.NumberField(Pane_.left())
  def top: Fields.NumberField[JavaPane] = new Fields.NumberField(Pane_.top())
  def atTop: Fields.FlagField[JavaPane] = new Fields.FlagField(Pane_.atTop())
  def atBottom: Fields.FlagField[JavaPane] =
    new Fields.FlagField(Pane_.atBottom())
  def atLeft: Fields.FlagField[JavaPane] = new Fields.FlagField(Pane_.atLeft())
  def atRight: Fields.FlagField[JavaPane] =
    new Fields.FlagField(Pane_.atRight())

  // Nested, not top-level: a top-level `extension (self: Pane) def asJava` in this file would share
  // a name with Server/Session/Window/Client's own top-level `asJava`, and Scala 3 requires
  // same-named top-level definitions to share one compilation unit ("the same group of toplevel
  // definitions"). Nested here, it is found through Pane's own extension-method implicit scope
  // instead, with no cross-file collision.
  extension (self: Pane) {

    /** The underlying Java pane. Opaque wrapping is free, so this never copies
      * scope or state.
      */
    def asJava: JavaPane = self

    // WAIT, handwritten: bespoke cancellation and a deadline, not a per-operation forward.

    /** Waits until `text` appears, or `timeout` passes first. */
    def awaitText(text: String, timeout: FiniteDuration): TextOutcome =
      self.asJava.awaitText(text, timeout.toJava)

    /** As `awaitText(text, timeout)`, looking every `every`: each look is a
      * tmux process.
      */
    def awaitText(
        text: String,
        timeout: FiniteDuration,
        every: FiniteDuration
    ): TextOutcome =
      self.asJava.awaitText(text, timeout.toJava, every.toJava)

    /** Runs a shell command in this pane to its end, and answers with its exit
      * status and output.
      */
    def run(command: String, timeout: FiniteDuration): PaneRun =
      self.asJava.run(command, timeout.toJava)

    /** Waits until `settled` holds for this pane as it is now. `settled`
      * receives a fresh capture each look.
      */
    def await(settled: Pane => Boolean, timeout: FiniteDuration): WakeReason =
      self.asJava.await(fresh => settled(Pane.wrap(fresh)), timeout.toJava)

    /** As `await(settled, timeout)`, looking every `every`. */
    def await(
        settled: Pane => Boolean,
        timeout: FiniteDuration,
        every: FiniteDuration
    ): WakeReason =
      self.asJava.await(
        fresh => settled(Pane.wrap(fresh)),
        timeout.toJava,
        every.toJava
      )
  }

  extension (panes: Vector[Pane]) {

    /** The captured panes `expr` matches: a local filter, evaluated against
      * what is already held.
      */
    def matching(expr: Expr[JavaPane]): Vector[Pane] =
      panes.filter(pane => expr.matches(pane.asJava))
  }
}
