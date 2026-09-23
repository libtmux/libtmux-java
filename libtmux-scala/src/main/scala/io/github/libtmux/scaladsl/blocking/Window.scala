package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{
  Dimensions,
  Layout,
  SplitSpec,
  Window => JavaWindow,
  WindowLayout
}
import io.github.libtmux.scaladsl.WindowInfo
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** One captured window placement. Equality includes its session and index. */
final class Window private[blocking] (
    private[scaladsl] val asJava: JavaWindow,
    val server: Server
) {

  /** The Java window link. It keeps none of this facade's scope. */
  def unsafeJava: JavaWindow = asJava
  val info: WindowInfo = WindowInfo(
    asJava.context(),
    asJava.name(),
    asJava.active(),
    asJava.panes().size(),
    asJava.linked(),
    asJava.size(),
    asJava.layout().value()
  )
  def session: Session = new Session(asJava.session(), server)
  def panes: Vector[Pane] =
    asJava.panes().asScala.iterator.map(new Pane(_, server)).toVector
  def activePane: Option[Pane] =
    asJava.activePane().toScala.map(new Pane(_, server))

  def split(): Pane = server.checked(new Pane(asJava.split(), server))
  def split(spec: SplitSpec): Pane =
    server.checked(new Pane(asJava.split(spec), server))
  def select(): Unit = server.checked(asJava.select())
  def selectLayout(layout: Layout): Unit =
    server.checked(asJava.selectLayout(layout))
  def applyLayout(layout: WindowLayout): Unit =
    server.checked(asJava.applyLayout(layout))
  def applyLayout(layout: String): Unit =
    server.checked(asJava.applyLayout(layout))
  def nextLayout(): Unit = server.checked(asJava.nextLayout())
  def previousLayout(): Unit = server.checked(asJava.previousLayout())
  def resizeTo(size: Dimensions): Unit = server.checked(asJava.resizeTo(size))
  def linkTo(session: Session): Unit =
    server.checked(asJava.linkTo(session.asJava))
  def unlink(): Unit = server.checked(asJava.unlink())
  def moveTo(session: Session): Unit =
    server.checked(asJava.moveTo(session.asJava))
  def moveTo(session: Session, index: Int): Unit =
    server.checked(asJava.moveTo(session.asJava, index))
  def synchronizePanes(): Unit = server.checked(asJava.synchronizePanes())
  def stopSynchronizingPanes(): Unit =
    server.checked(asJava.stopSynchronizingPanes())
  def rotate(): Unit = server.checked(asJava.rotate())
  def expand(format: String): String = server.checked(asJava.expand(format))
  def kill(): Unit = server.checked(asJava.kill())
  def options: Options = new Options(asJava.options(), server)
  def hooks: Hooks = new Hooks(asJava.hooks(), server)

  def rename(name: String): Window =
    server.checked(new Window(asJava.rename(name), server))
  def refresh(): Window = server.checked(new Window(asJava.refresh(), server))

  override def equals(other: Any): Boolean = other match {
    case that: Window => asJava == that.asJava
    case _            => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

object Window {
  def fromJava(window: JavaWindow): Window =
    new Window(window, Server.fromJava(window.server()))
}
