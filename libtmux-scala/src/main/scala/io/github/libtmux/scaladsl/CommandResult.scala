package io.github.libtmux.scaladsl

import io.github.libtmux.transport.{CommandResult => JavaResult}
import scala.jdk.CollectionConverters._

/** Completed raw invocation. Nonzero exit is data; output is not normalized
  * again.
  */
final class CommandResult private (private[scaladsl] val asJava: JavaResult) {
  def unsafeJava: JavaResult = asJava
  val exitCode: Int = asJava.exitCode()
  val stdout: Vector[String] = asJava.stdout().asScala.toVector
  val stderr: Vector[String] = asJava.stderr().asScala.toVector
  val succeeded: Boolean = asJava.succeeded()
  override def toString: String = asJava.toString
}

object CommandResult {
  private[scaladsl] def fromJava(value: JavaResult): CommandResult =
    new CommandResult(value)
}

/** A pane command's captured result; cancellation or timeout is not rollback.
  */
final class PaneRun private (
    private[scaladsl] val asJava: io.github.libtmux.PaneRun
) {
  def unsafeJava: io.github.libtmux.PaneRun = asJava
  import scala.jdk.OptionConverters._

  val outcome: io.github.libtmux.PaneRun.Outcome = asJava.outcome()
  val exitStatus: Option[Int] = asJava.exitStatus().toScala
  val output: Vector[String] = asJava.output().asScala.toVector
  val exact: Boolean = asJava.exact()
  val succeeded: Boolean = asJava.succeeded()
  override def toString: String =
    s"PaneRun($outcome, exitStatus=$exitStatus, outputLines=${output.size}, exact=$exact)"
}

object PaneRun {
  private[scaladsl] def fromJava(value: io.github.libtmux.PaneRun): PaneRun =
    new PaneRun(value)
}
