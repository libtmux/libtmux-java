package io.github.libtmux.scaladsl.cats

import io.github.libtmux.batch.BatchResult
import io.github.libtmux.{CommandChain => JavaCommandChain}
import scala.jdk.CollectionConverters._

/** An immutable plan using tmux's evolving current target. Construction does no
  * tmux I/O; each `run` builds a fresh Java chain from `create` and dispatches
  * it through `Execution`, F-suspended and cancellation-safe like every other
  * Cats operation.
  */
final class CommandChain[F[_]] private[cats] (
    create: () => JavaCommandChain,
    execution: Execution[F],
    steps: Vector[JavaCommandChain => JavaCommandChain] = Vector.empty
) {
  private def append(
      step: JavaCommandChain => JavaCommandChain
  ): CommandChain[F] = new CommandChain(create, execution, steps :+ step)

  def newWindow(name: String): CommandChain[F] = append(_.newWindow(name))
  def renameWindow(name: String): CommandChain[F] = append(_.renameWindow(name))
  def splitLeftRight(): CommandChain[F] = append(_.splitLeftRight())
  def splitTopBottom(): CommandChain[F] = append(_.splitTopBottom())
  def sendLine(command: String): CommandChain[F] = append(_.sendLine(command))

  /** Layout validation and its possible version query occur during run. */
  def arrange(layout: String): CommandChain[F] = append(_.arrange(layout))

  def `then`(argv: Seq[String]): CommandChain[F] = {
    val copied = argv.toVector.asJava
    append(_.`then`(copied))
  }

  def `then`(command: String, arguments: String*): CommandChain[F] =
    `then`(command +: arguments)

  def run: F[BatchResult] = execution {
    steps.foldLeft(create())((chain, step) => step(chain)).run()
  }
}
