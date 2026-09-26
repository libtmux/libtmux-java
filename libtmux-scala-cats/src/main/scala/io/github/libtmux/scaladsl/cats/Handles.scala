package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.Async
import io.github.libtmux.scaladsl as direct

/** Small final classes carrying `(underlying, server)` — real per-scope state
  * (`Execution[F]`, on `Server[F]`) an opaque alias cannot carry, so these are
  * ordinary classes rather than opaque aliases of the direct-style handles they
  * wrap. Every operation beyond equality is an extension, generated from the
  * operation catalog or handwritten where the catalog marks it `WAIT`, `STREAM`
  * or `LIFECYCLE` — never `export`ed, since `export` cannot forward an
  * extension method and every accessor on a direct-style opaque handle is one
  * by construction.
  */
final class Session[F[_]] private[cats] (
    private[cats] val underlying: direct.Session,
    val server: Server[F]
)(implicit
    F: Async[F]
) {
  override def equals(other: Any): Boolean = other match {
    case that: Session[?] => underlying == that.underlying
    case _                => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Session = underlying.asJava
}

final class Window[F[_]] private[cats] (
    private[cats] val underlying: direct.Window,
    val server: Server[F]
)(implicit
    F: Async[F]
) {
  override def equals(other: Any): Boolean = other match {
    case that: Window[?] => underlying == that.underlying
    case _               => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Window = underlying.asJava
}

final class Pane[F[_]] private[cats] (
    private[cats] val underlying: direct.Pane,
    val server: Server[F]
)(implicit
    F: Async[F]
) {
  override def equals(other: Any): Boolean = other match {
    case that: Pane[?] => underlying == that.underlying
    case _             => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Pane = underlying.asJava
}

/** `WAIT`-kind operations: handwritten, since the catalog's own facade mapping
  * reserves them for bespoke cancellation, not a per-operation forward. Each
  * still routes through [[Execution#waiting]], which reserves the shared-wait
  * capacity `Execution.resource` set aside, so a wait cannot take the last slot
  * an ordinary call needs.
  *
  * In `Pane`'s own companion, defined here beside the class: a top-level `run`
  * extension in a different file would share a name with the generated
  * `Server[F].run` — Scala 3 requires same-named top-level definitions to share
  * one compilation unit — and a class/object pair is only treated as companions
  * when they share a file, so this lives here rather than in its own.
  */
object Pane {
  extension [F[_]](self: Pane[F])(using F: Async[F]) {

    // Handwritten, not generated: see Server.scala's own batch/chain extension for why.

    /** An immutable batch plan; each run dispatches a fresh Java `Batch`
      * through this pane's server's `Execution`.
      */
    def batch: Batch[F] =
      new Batch(
        () => self.underlying.asJava.batch(),
        self.server.execution
      )

    /** Waits until `text` appears, or `timeout` passes first. */
    def awaitText(
        text: String,
        timeout: scala.concurrent.duration.FiniteDuration
    ): F[io.github.libtmux.TextOutcome] =
      self.server.execution.waiting(
        self.underlying.asJava.awaitText(
          text,
          scala.jdk.DurationConverters.ScalaDurationOps(timeout).toJava
        )
      )

    /** As `awaitText(text, timeout)`, looking every `every`: each look is a
      * tmux process.
      */
    def awaitText(
        text: String,
        timeout: scala.concurrent.duration.FiniteDuration,
        every: scala.concurrent.duration.FiniteDuration
    ): F[io.github.libtmux.TextOutcome] =
      self.server.execution.waiting(
        self.underlying.asJava.awaitText(
          text,
          scala.jdk.DurationConverters.ScalaDurationOps(timeout).toJava,
          scala.jdk.DurationConverters.ScalaDurationOps(every).toJava
        )
      )

    /** Runs a shell command in this pane to its end, and answers with its exit
      * status and output.
      */
    def run(
        command: String,
        timeout: scala.concurrent.duration.FiniteDuration
    ): F[io.github.libtmux.PaneRun] =
      self.server.execution.waiting(
        self.underlying.asJava.run(
          command,
          scala.jdk.DurationConverters.ScalaDurationOps(timeout).toJava
        )
      )

    /** Waits until `settled` holds for this pane as it is now. `settled` runs
      * on the blocking worker with each fresh capture.
      */
    def await(
        settled: Pane[F] => Boolean,
        timeout: scala.concurrent.duration.FiniteDuration
    ): F[io.github.libtmux.WakeReason] =
      self.server.execution.waiting(
        self.underlying.asJava.await(
          fresh => settled(self.server.pane(direct.Pane.wrap(fresh))),
          scala.jdk.DurationConverters.ScalaDurationOps(timeout).toJava
        )
      )

    /** As `await(settled, timeout)`, looking every `every`. */
    def await(
        settled: Pane[F] => Boolean,
        timeout: scala.concurrent.duration.FiniteDuration,
        every: scala.concurrent.duration.FiniteDuration
    ): F[io.github.libtmux.WakeReason] =
      self.server.execution.waiting(
        self.underlying.asJava.await(
          fresh => settled(self.server.pane(direct.Pane.wrap(fresh))),
          scala.jdk.DurationConverters.ScalaDurationOps(timeout).toJava,
          scala.jdk.DurationConverters.ScalaDurationOps(every).toJava
        )
      )
  }
}

final class Client[F[_]] private[cats] (
    private[cats] val underlying: direct.Client,
    val server: Server[F]
)(implicit
    F: Async[F]
) {
  override def equals(other: Any): Boolean = other match {
    case that: Client[?] => underlying == that.underlying
    case _               => false
  }
  override def hashCode(): Int = underlying.hashCode()
  override def toString: String = underlying.toString

  /** The Java handle this wraps: the one escape hatch, as on every handle. */
  def asJava: io.github.libtmux.Client = underlying.asJava
}
