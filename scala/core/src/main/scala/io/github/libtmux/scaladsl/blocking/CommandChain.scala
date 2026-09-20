package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{CommandChain => JavaCommandChain}
import io.github.libtmux.scaladsl.BatchResult
import scala.jdk.CollectionConverters._

/** A mutable group whose commands use tmux's evolving current target. Earlier
  * effects survive later failures; result attribution remains Java's report.
  */
final class CommandChain private[blocking] (
    val asJava: JavaCommandChain,
    server: Server
) {
  private def append(operation: => JavaCommandChain): CommandChain =
    server.checked {
      operation
      this
    }

  def newWindow(name: String): CommandChain = append(asJava.newWindow(name))
  def renameWindow(name: String): CommandChain =
    append(asJava.renameWindow(name))
  def splitLeftRight(): CommandChain = append(asJava.splitLeftRight())
  def splitTopBottom(): CommandChain = append(asJava.splitTopBottom())
  def sendLine(command: String): CommandChain = append(asJava.sendLine(command))

  /** Java validates the layout against tmux here, which can perform I/O. */
  def arrange(layout: String): CommandChain = append(asJava.arrange(layout))
  def andThen(argv: Seq[String]): CommandChain = append(
    asJava.`then`(argv.toVector.asJava)
  )
  def andThen(command: String, arguments: String*): CommandChain =
    andThen(command +: arguments)

  def run(): BatchResult = server.checked(BatchResult.fromJava(asJava.run()))
}
