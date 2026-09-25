package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.Async
import _root_.cats.syntax.all._
import io.github.libtmux.{
  CaptureSpec,
  Client => JavaClient,
  Dimensions,
  Direction,
  Layout,
  Pane => JavaPane,
  PaneMode,
  Session => JavaSession,
  SplitSpec,
  TextOutcome,
  WakeReason,
  Window => JavaWindow,
  WindowLayout,
  WindowSpec
}
import io.github.libtmux.scaladsl.{
  ClientInfo,
  PaneInfo,
  PaneRun,
  SessionInfo,
  WindowInfo,
  blocking
}
import java.nio.file.Path
import java.time.Duration
import scala.collection.immutable.VectorMap

/** A captured session. Traversal is pure; operations run in the server scope.
  */
final class Session[F[_]] private[cats] (
    private[cats] val underlying: blocking.Session,
    val server: Server[F]
)(implicit F: Async[F]) {

  /** The Java session. It keeps none of this resource's scope. */
  def unsafeJava: JavaSession = asJava
  private[scaladsl] val asJava: JavaSession = underlying.asJava
  val info: SessionInfo = underlying.info
  def windows: Vector[Window[F]] = underlying.windows.map(server.window)
  def activeWindow: Option[Window[F]] =
    underlying.activeWindow.map(server.window)
  def activePane: Option[Pane[F]] = underlying.activePane.map(server.pane)
  def newWindow(name: String): F[Window[F]] =
    server.execution(underlying.newWindow(name)).map(server.window)
  def newWindow(spec: WindowSpec): F[Window[F]] =
    server.execution(underlying.newWindow(spec)).map(server.window)
  def rename(name: String): F[Session[F]] =
    server.execution(underlying.rename(name)).map(server.session)
  def selectWindow(window: Window[F]): F[Unit] =
    server.execution(underlying.selectWindow(window.underlying))
  def nextWindow: F[Unit] = server.execution(underlying.nextWindow())
  def previousWindow: F[Unit] = server.execution(underlying.previousWindow())
  def lastWindow: F[Unit] = server.execution(underlying.lastWindow())
  def detachClients: F[Unit] = server.execution(underlying.detachClients())
  def expand(format: String): F[String] =
    server.execution(underlying.expand(format))
  def setHistoryLimit(lines: Int): F[Unit] =
    server.execution(underlying.setHistoryLimit(lines))
  def kill: F[Unit] = server.execution(underlying.kill())
  def refresh: F[Session[F]] =
    server.execution(underlying.refresh()).map(server.session)
  def options: Options[F] = new Options(underlying.options, server.execution)
  def environment: Environment[F] =
    new Environment(underlying.environment, server.execution)
  def hooks: Hooks[F] = new Hooks(underlying.hooks, server.execution)

  override def equals(other: Any): Boolean = other match {
    case that: Session[F] @unchecked => asJava == that.asJava
    case _                           => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

/** A captured window placement. Equality includes its session and index. */
final class Window[F[_]] private[cats] (
    private[cats] val underlying: blocking.Window,
    val server: Server[F]
)(implicit F: Async[F]) {

  /** The Java window link. It keeps none of this resource's scope. */
  def unsafeJava: JavaWindow = asJava
  private[scaladsl] val asJava: JavaWindow = underlying.asJava
  val info: WindowInfo = underlying.info
  def session: Session[F] = server.session(underlying.session)
  def panes: Vector[Pane[F]] = underlying.panes.map(server.pane)
  def activePane: Option[Pane[F]] = underlying.activePane.map(server.pane)
  def split: F[Pane[F]] = server.execution(underlying.split()).map(server.pane)
  def split(spec: SplitSpec): F[Pane[F]] =
    server.execution(underlying.split(spec)).map(server.pane)
  def select: F[Unit] = server.execution(underlying.select())
  def selectLayout(layout: Layout): F[Unit] =
    server.execution(underlying.selectLayout(layout))
  def applyLayout(layout: WindowLayout): F[Unit] =
    server.execution(underlying.applyLayout(layout))
  def applyLayout(layout: String): F[Unit] =
    server.execution(underlying.applyLayout(layout))
  def nextLayout: F[Unit] = server.execution(underlying.nextLayout())
  def previousLayout: F[Unit] = server.execution(underlying.previousLayout())
  def resizeTo(size: Dimensions): F[Unit] =
    server.execution(underlying.resizeTo(size))
  def linkTo(session: Session[F]): F[Unit] =
    server.execution(underlying.linkTo(session.underlying))
  def unlink: F[Unit] = server.execution(underlying.unlink())
  def moveTo(session: Session[F]): F[Unit] =
    server.execution(underlying.moveTo(session.underlying))
  def moveTo(session: Session[F], index: Int): F[Unit] =
    server.execution(underlying.moveTo(session.underlying, index))
  def synchronizePanes: F[Unit] =
    server.execution(underlying.synchronizePanes())
  def stopSynchronizingPanes: F[Unit] =
    server.execution(underlying.stopSynchronizingPanes())
  def rotate: F[Unit] = server.execution(underlying.rotate())
  def respawn: F[Unit] = server.execution(underlying.respawn())

  /** Draws a popup for an attached client; tmux expands `#(...)` first. */
  def displayPopup(shellCommand: String): F[Unit] =
    server.execution(underlying.displayPopup(shellCommand))
  def expand(format: String): F[String] =
    server.execution(underlying.expand(format))
  def kill: F[Unit] = server.execution(underlying.kill())
  def rename(name: String): F[Window[F]] =
    server.execution(underlying.rename(name)).map(server.window)
  def refresh: F[Window[F]] =
    server.execution(underlying.refresh()).map(server.window)
  def options: Options[F] = new Options(underlying.options, server.execution)
  def hooks: Hooks[F] = new Hooks(underlying.hooks, server.execution)

  override def equals(other: Any): Boolean = other match {
    case that: Window[F] @unchecked => asJava == that.asJava
    case _                          => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

/** A captured pane occurrence. Equality retains Java's physical identity. */
final class Pane[F[_]] private[cats] (
    private[cats] val underlying: blocking.Pane,
    val server: Server[F]
)(implicit F: Async[F]) {

  /** The Java pane. It keeps none of this resource's scope. */
  def unsafeJava: JavaPane = asJava
  private[scaladsl] val asJava: JavaPane = underlying.asJava
  val info: PaneInfo = underlying.info
  def window: Window[F] = server.window(underlying.window)
  def capture: F[Vector[String]] = server.execution(underlying.capture())
  def batch: Batch[F] = new Batch(() => underlying.batch(), server.execution)
  def capture(spec: CaptureSpec): F[Vector[String]] =
    server.execution(underlying.capture(spec))
  def send(keys: String): F[Unit] = server.execution(underlying.send(keys))
  def sendKeys(keys: Seq[String]): F[Unit] =
    server.execution(underlying.sendKeys(keys))

  /** As `sendKeys(keys)`, after `beforeSend` confirms this caller still owns
    * the pane's input. It runs on the blocking worker, inside the pane's hold,
    * just before the keys go; if it throws, nothing is sent.
    */
  def sendKeys(keys: Seq[String], beforeSend: () => Unit): F[Unit] =
    server.execution(underlying.sendKeys(keys, beforeSend))
  def sendLiteral(text: String): F[Unit] =
    server.execution(underlying.sendLiteral(text))
  def sendLiteral(parts: Seq[String]): F[Unit] =
    server.execution(underlying.sendLiteral(parts))

  /** As `sendLiteral(parts)`, after `beforeSend`, as `sendKeys` runs it. */
  def sendLiteral(parts: Seq[String], beforeSend: () => Unit): F[Unit] =
    server.execution(underlying.sendLiteral(parts, beforeSend))
  def sendLine(command: String): F[Unit] =
    server.execution(underlying.sendLine(command))
  def awaitText(text: String, timeout: Duration): F[TextOutcome] =
    server.execution.waiting(underlying.awaitText(text, timeout))

  /** As `awaitText(text, timeout)`, looking every `every`: each look is a tmux
    * process.
    */
  def awaitText(
      text: String,
      timeout: Duration,
      every: Duration
  ): F[TextOutcome] =
    server.execution.waiting(underlying.awaitText(text, timeout, every))

  /** Waits until `settled` holds for this pane as it is now. `settled` runs on
    * the blocking worker with each fresh capture, so read its `info`.
    */
  def await(settled: Pane[F] => Boolean, timeout: Duration): F[WakeReason] =
    server.execution.waiting(
      underlying.await(fresh => settled(server.pane(fresh)), timeout)
    )

  /** As `await(settled, timeout)`, looking every `every`. */
  def await(
      settled: Pane[F] => Boolean,
      timeout: Duration,
      every: Duration
  ): F[WakeReason] = server.execution.waiting(
    underlying.await(fresh => settled(server.pane(fresh)), timeout, every)
  )
  def run(command: String, timeout: Duration): F[PaneRun] =
    server.execution(underlying.run(command, timeout))
  def copyMode: F[Unit] = server.execution(underlying.copyMode())
  def mode: F[Option[PaneMode]] = server.execution(underlying.mode())
  def exitMode: F[Unit] = server.execution(underlying.exitMode())
  def dead: F[Boolean] = server.execution(underlying.dead())
  def select: F[Unit] = server.execution(underlying.select())
  def retitle(title: String): F[Pane[F]] =
    server.execution(underlying.retitle(title)).map(server.pane)
  def resize(direction: Direction, cells: Int): F[Unit] =
    server.execution(underlying.resize(direction, cells))
  def resizeTo(size: Dimensions): F[Unit] =
    server.execution(underlying.resizeTo(size))
  def split: F[Pane[F]] = server.execution(underlying.split()).map(server.pane)
  def split(spec: SplitSpec): F[Pane[F]] =
    server.execution(underlying.split(spec)).map(server.pane)
  def breakOut: F[Window[F]] =
    server.execution(underlying.breakOut()).map(server.window)
  def breakOut(name: String): F[Window[F]] =
    server.execution(underlying.breakOut(name)).map(server.window)
  def joinTo(window: Window[F]): F[Unit] =
    server.execution(underlying.joinTo(window.underlying))
  def swapWith(pane: Pane[F]): F[Unit] =
    server.execution(underlying.swapWith(pane.underlying))
  def expand(format: String): F[String] =
    server.execution(underlying.expand(format))
  def variables(names: Seq[String]): F[VectorMap[String, String]] =
    server.execution(underlying.variables(names))
  def respawn: F[Unit] = server.execution(underlying.respawn())
  def respawn(command: Seq[String]): F[Unit] =
    server.execution(underlying.respawn(command))
  def respawnIn(directory: Path): F[Unit] =
    server.execution(underlying.respawnIn(directory))

  /** Sends what this pane prints to a shell command until `stopPiping`. */
  def pipeTo(shellCommand: String): F[Unit] =
    server.execution(underlying.pipeTo(shellCommand))
  def stopPiping: F[Unit] = server.execution(underlying.stopPiping())
  def paste(text: String): F[Unit] = server.execution(underlying.paste(text))

  /** As `paste(text)`, after `beforePaste`, as `sendKeys` runs its check. */
  def paste(text: String, beforePaste: () => Unit): F[Unit] =
    server.execution(underlying.paste(text, beforePaste))
  def pasteBuffer(name: String): F[Unit] =
    server.execution(underlying.pasteBuffer(name))
  def clearHistory: F[Unit] = server.execution(underlying.clearHistory())
  def kill: F[Unit] = server.execution(underlying.kill())
  def refresh: F[Pane[F]] =
    server.execution(underlying.refresh()).map(server.pane)
  def options: Options[F] = new Options(underlying.options, server.execution)
  def hooks: Hooks[F] = new Hooks(underlying.hooks, server.execution)

  override def equals(other: Any): Boolean = other match {
    case that: Pane[F] @unchecked => asJava == that.asJava
    case _                        => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

final case class ClientAttachment[F[_]](
    session: Session[F],
    activeWindow: Window[F],
    activePane: Pane[F]
)

/** A captured attached client. Refresh and mutation run in the server scope. */
final class Client[F[_]] private[cats] (
    private[cats] val underlying: blocking.Client,
    val server: Server[F]
)(implicit F: Async[F]) {

  /** The Java client. It keeps none of this resource's scope. */
  def unsafeJava: JavaClient = asJava
  private[scaladsl] val asJava: JavaClient = underlying.asJava
  val info: ClientInfo = underlying.info
  private def wrap(value: blocking.ClientAttachment): ClientAttachment[F] =
    ClientAttachment(
      server.session(value.session),
      server.window(value.activeWindow),
      server.pane(value.activePane)
    )
  def session: Option[Session[F]] = underlying.session.map(server.session)
  def attachment: Option[ClientAttachment[F]] = underlying.attachment.map(wrap)
  def refresh: F[Client[F]] =
    server.execution(underlying.refresh()).map(server.client)
  def fetchAttachment: F[Option[ClientAttachment[F]]] =
    server.execution(underlying.fetchAttachment()).map(_.map(wrap))
  def detach: F[Unit] = server.execution(underlying.detach())
  def detachOthers: F[Unit] = server.execution(underlying.detachOthers())
  def switchTo(session: Session[F]): F[Unit] =
    server.execution(underlying.switchTo(session.underlying))
  def redraw: F[Unit] = server.execution(underlying.redraw())

  override def equals(other: Any): Boolean = other match {
    case that: Client[F] @unchecked => asJava == that.asJava
    case _                          => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}
