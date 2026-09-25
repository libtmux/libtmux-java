package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{Session => JavaSession, WindowSpec}
import io.github.libtmux.scaladsl.SessionInfo
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** An operational handle with immutable captured information and traversal. */
final class Session private[blocking] (
    private[scaladsl] val asJava: JavaSession,
    val server: Server
) {

  /** The Java session. It keeps none of this facade's scope. */
  def unsafeJava: JavaSession = asJava
  val info: SessionInfo = SessionInfo(
    asJava.id(),
    asJava.name(),
    asJava.attached(),
    asJava.windows().size()
  )
  def windows: Vector[Window] =
    asJava.windows().asScala.iterator.map(new Window(_, server)).toVector
  def activeWindow: Option[Window] =
    asJava.activeWindow().toScala.map(new Window(_, server))
  def activePane: Option[Pane] =
    asJava.activePane().toScala.map(new Pane(_, server))

  def newWindow(name: String): Window =
    server.checked(new Window(asJava.newWindow(name), server))
  def newWindow(spec: WindowSpec): Window =
    server.checked(new Window(asJava.newWindow(spec), server))
  def rename(name: String): Session =
    server.checked(new Session(asJava.rename(name), server))
  def selectWindow(window: Window): Unit =
    server.checked(asJava.selectWindow(window.asJava))
  def nextWindow(): Unit = server.checked(asJava.nextWindow())
  def previousWindow(): Unit = server.checked(asJava.previousWindow())
  def lastWindow(): Unit = server.checked(asJava.lastWindow())
  def detachClients(): Unit = server.checked(asJava.detachClients())
  def expand(format: String): String = server.checked(asJava.expand(format))

  /** Sets the scrollback kept by panes created in this session from now on. */
  def setHistoryLimit(lines: Int): Unit =
    server.checked(asJava.setHistoryLimit(lines))
  def kill(): Unit = server.checked(asJava.kill())
  def options: Options = new Options(asJava.options(), server)
  def environment: Environment = new Environment(asJava.environment(), server)
  def hooks: Hooks = new Hooks(asJava.hooks(), server)

  def refresh(): Session = server.checked(new Session(asJava.refresh(), server))

  override def equals(other: Any): Boolean = other match {
    case that: Session => asJava == that.asJava
    case _             => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

object Session {
  def fromJava(session: JavaSession): Session =
    new Session(session, Server.fromJava(session.server()))
}
