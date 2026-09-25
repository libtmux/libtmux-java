package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{
  CaptureSpec,
  Dimensions,
  Direction,
  Pane => JavaPane,
  PaneMode,
  SplitSpec,
  TextOutcome,
  WakeReason
}
import java.nio.file.Path
import java.time.Duration
import scala.collection.immutable.VectorMap
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import io.github.libtmux.scaladsl.{PaneInfo, PaneRun}

/** A captured pane occurrence; Java equality identifies its physical pane. */
final class Pane private[blocking] (
    private[scaladsl] val asJava: JavaPane,
    val server: Server
) {

  /** The Java pane. It keeps none of this facade's scope. */
  def unsafeJava: JavaPane = asJava
  val info: PaneInfo = PaneInfo.fromHandle(asJava)
  def window: Window = new Window(asJava.window(), server)
  def batch(): Batch = server.checked(new Batch(asJava.batch(), server))

  def capture(): Vector[String] =
    server.checked(asJava.capture().asScala.toVector)
  def capture(spec: CaptureSpec): Vector[String] =
    server.checked(asJava.capture(spec).asScala.toVector)
  def send(keys: String): Unit = server.checked(asJava.send(keys))
  def sendKeys(keys: Seq[String]): Unit =
    server.checked(asJava.sendKeys(keys.asJava))

  /** As `sendKeys(keys)`, after `beforeSend` confirms this caller still owns
    * the pane's input. If it throws, nothing is sent.
    */
  def sendKeys(keys: Seq[String], beforeSend: () => Unit): Unit =
    server.checked(asJava.sendKeys(keys.asJava, () => beforeSend()))
  def sendLiteral(text: String): Unit = sendLiteral(Vector(text))
  def sendLiteral(parts: Seq[String]): Unit =
    server.checked(asJava.sendLiteral(parts.asJava))
  def sendLiteral(parts: Seq[String], beforeSend: () => Unit): Unit =
    server.checked(asJava.sendLiteral(parts.asJava, () => beforeSend()))
  def sendLine(command: String): Unit = server.checked(asJava.sendLine(command))
  def awaitText(text: String, timeout: Duration): TextOutcome =
    server.checked(asJava.awaitText(text, timeout))

  /** As `awaitText(text, timeout)`, looking every `every`: each look is a tmux
    * process.
    */
  def awaitText(text: String, timeout: Duration, every: Duration): TextOutcome =
    server.checked(asJava.awaitText(text, timeout, every))

  /** Waits until `settled` holds for this pane as it is now, looking every 50
    * ms; `settled` receives a fresh capture each look.
    */
  def await(settled: Pane => Boolean, timeout: Duration): WakeReason =
    server.checked(
      asJava.await(fresh => settled(new Pane(fresh, server)), timeout)
    )

  /** As `await(settled, timeout)`, looking every `every`. */
  def await(
      settled: Pane => Boolean,
      timeout: Duration,
      every: Duration
  ): WakeReason = server.checked(
    asJava.await(fresh => settled(new Pane(fresh, server)), timeout, every)
  )
  def run(command: String, timeout: Duration): PaneRun =
    server.checked(PaneRun.fromJava(asJava.run(command, timeout)))
  def copyMode(): Unit = server.checked(asJava.copyMode())
  def mode(): Option[PaneMode] = server.checked(asJava.mode().toScala)
  def exitMode(): Unit = server.checked(asJava.exitMode())
  def dead(): Boolean = server.checked(asJava.dead())
  def select(): Unit = server.checked(asJava.select())
  def retitle(title: String): Pane =
    server.checked(new Pane(asJava.retitle(title), server))
  def resize(direction: Direction, cells: Int): Unit =
    server.checked(asJava.resize(direction, cells))
  def resizeTo(size: Dimensions): Unit = server.checked(asJava.resizeTo(size))
  def split(): Pane = server.checked(new Pane(asJava.split(), server))
  def split(spec: SplitSpec): Pane =
    server.checked(new Pane(asJava.split(spec), server))
  def breakOut(): Window = server.checked(new Window(asJava.breakOut(), server))
  def breakOut(name: String): Window =
    server.checked(new Window(asJava.breakOut(name), server))
  def joinTo(window: Window): Unit =
    server.checked(asJava.joinTo(window.asJava))
  def swapWith(pane: Pane): Unit = server.checked(asJava.swapWith(pane.asJava))
  def expand(format: String): String = server.checked(asJava.expand(format))

  /** tmux variables in this pane's context, in the order asked for. */
  def variables(names: Seq[String]): VectorMap[String, String] =
    server.checked(VectorMap.from(asJava.variables(names.asJava).asScala))

  /** Kills what runs here and starts the pane's default command again. */
  def respawn(): Unit = server.checked(asJava.respawn())

  /** Kills what runs here and starts `command` instead. */
  def respawn(command: Seq[String]): Unit =
    server.checked(asJava.respawn(command: _*))
  def respawnIn(directory: Path): Unit =
    server.checked(asJava.respawnIn(directory))

  /** Sends what this pane prints to a shell command until `stopPiping`; tmux
    * expands `#(...)` in it first.
    */
  def pipeTo(shellCommand: String): Unit =
    server.checked(asJava.pipeTo(shellCommand))
  def stopPiping(): Unit = server.checked(asJava.stopPiping())
  def paste(text: String): Unit = server.checked(asJava.paste(text))
  def paste(text: String, beforePaste: () => Unit): Unit =
    server.checked(asJava.paste(text, () => beforePaste()))
  def pasteBuffer(name: String): Unit = server.checked(asJava.pasteBuffer(name))
  def clearHistory(): Unit = server.checked(asJava.clearHistory())
  def kill(): Unit = server.checked(asJava.kill())
  def options: Options = new Options(asJava.options(), server)
  def hooks: Hooks = new Hooks(asJava.hooks(), server)

  /** Refreshes physical identity; Java may return a different link context. */
  def refresh(): Pane = server.checked(new Pane(asJava.refresh(), server))

  override def equals(other: Any): Boolean = other match {
    case that: Pane => asJava == that.asJava
    case _          => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

object Pane {
  def fromJava(pane: JavaPane): Pane =
    new Pane(pane, Server.fromJava(pane.server()))
}
