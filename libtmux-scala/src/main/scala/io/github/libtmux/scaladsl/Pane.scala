package io.github.libtmux.scaladsl

import io.github.libtmux.{Pane => JavaPane, PaneRun, TextOutcome, WakeReason}
import io.github.libtmux.scaladsl.query.Expr
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

  // Generated from field-catalog.tsv (project/ScalaFieldCodegen.scala), in its own object and
  // package so it can live in a separate generated file: a class and object of the same name are
  // companions, sharing private access, only when they share one file, so a generated `object
  // Pane` here would either fail to compile alongside this one or silently stop being this type's
  // companion. Re-exporting is the one-line bridge back.
  export io.github.libtmux.scaladsl.generated.PaneFields.*

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
