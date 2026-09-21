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
import java.time.Duration

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
  def sendLiteral(text: String): F[Unit] =
    server.execution(underlying.sendLiteral(text))
  def sendLiteral(parts: Seq[String]): F[Unit] =
    server.execution(underlying.sendLiteral(parts))
  def sendLine(command: String): F[Unit] =
    server.execution(underlying.sendLine(command))
  def awaitText(text: String, timeout: Duration): F[TextOutcome] =
    server.execution.waiting(underlying.awaitText(text, timeout))
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
  def paste(text: String): F[Unit] = server.execution(underlying.paste(text))
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
  def refresh: F[Option[Client[F]]] =
    server.execution(underlying.refresh()).map(_.map(server.client))
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
