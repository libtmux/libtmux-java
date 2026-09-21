package io.github.libtmux.scaladsl.cats

import io.github.libtmux.scaladsl.{BatchResult, blocking}

/** An immutable plan using tmux's evolving current target. Construction does no
  * tmux I/O; each run builds and executes a fresh Java chain.
  */
final class CommandChain[F[_]] private[cats] (
    create: () => blocking.CommandChain,
    execution: Execution[F],
    steps: Vector[blocking.CommandChain => blocking.CommandChain] = Vector.empty
) {
  private def append(
      step: blocking.CommandChain => blocking.CommandChain
  ): CommandChain[F] = new CommandChain(create, execution, steps :+ step)

  def newWindow(name: String): CommandChain[F] = append(_.newWindow(name))
  def renameWindow(name: String): CommandChain[F] = append(_.renameWindow(name))
  def splitLeftRight(): CommandChain[F] = append(_.splitLeftRight())
  def splitTopBottom(): CommandChain[F] = append(_.splitTopBottom())
  def sendLine(command: String): CommandChain[F] = append(_.sendLine(command))

  /** Layout validation and its possible version query occur during run. */
  def arrange(layout: String): CommandChain[F] = append(_.arrange(layout))

  def andThen(argv: Seq[String]): CommandChain[F] = {
    val copied = argv.toVector
    append(_.andThen(copied))
  }

  def andThen(command: String, arguments: String*): CommandChain[F] =
    andThen(command +: arguments)

  def run: F[BatchResult] = execution {
    steps.foldLeft(create())((chain, step) => step(chain)).run()
  }
}
