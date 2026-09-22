package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Dimensions,
  PaneEdges,
  PaneId,
  PanePosition,
  SessionId,
  TmuxVersion
}
import io.github.libtmux.snapshot.{
  ClientState,
  PaneState,
  ServerSnapshot,
  SessionState,
  WindowContext,
  WindowState
}
import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** Detached pane information. Path conversion is explicit and may fail. */
final case class PaneInfo(
    context: WindowContext,
    id: PaneId,
    index: Int,
    active: Boolean,
    currentCommand: String,
    size: Dimensions,
    position: PanePosition,
    title: String,
    currentPathText: String,
    pid: Option[Long],
    edges: PaneEdges,
    floating: Option[Boolean]
) {
  def currentPath(): Path = Paths.get(currentPathText)
}

object PaneInfo {
  def fromJava(value: PaneState): PaneInfo = PaneInfo(
    value.context(),
    value.id(),
    value.index(),
    value.active(),
    value.currentCommand(),
    value.size(),
    value.position(),
    value.title(),
    value.currentPath(),
    value.pid().toScala,
    value.edges(),
    value.floating().toScala.map(_.booleanValue())
  )

  private[scaladsl] def fromHandle(value: io.github.libtmux.Pane): PaneInfo =
    PaneInfo(
      value.window().context(),
      value.id(),
      value.index(),
      value.active(),
      value.currentCommand(),
      value.size(),
      value.position(),
      value.title(),
      value.currentPathText(),
      value.pid().toScala,
      value.edges(),
      value.floating().toScala.map(_.booleanValue())
    )
}

/** One window placement; physical windows can have several placements. */
final case class WindowInfo(
    context: WindowContext,
    name: String,
    active: Boolean,
    panes: Int,
    linked: Boolean,
    size: Dimensions,
    layout: String
)

object WindowInfo {
  def fromJava(value: WindowState): WindowInfo = WindowInfo(
    value.context(),
    value.name(),
    value.active(),
    value.panes(),
    value.linked(),
    value.size(),
    value.layout()
  )
}

final case class SessionInfo(
    id: SessionId,
    name: String,
    attached: Boolean,
    windows: Int
)

object SessionInfo {
  def fromJava(value: SessionState): SessionInfo =
    SessionInfo(value.id(), value.name(), value.attached(), value.windows())
}

final case class ClientInfo(name: String, session: Option[SessionId])

object ClientInfo {
  def fromJava(value: ClientState): ClientInfo =
    ClientInfo(value.name(), value.session().toScala)
}

/** A checked multi-list capture, not an atomic transaction or a live handle. */
final class Snapshot private (private[scaladsl] val asJava: ServerSnapshot) {
  def unsafeJava: ServerSnapshot = asJava
  val capturedAt: Instant = asJava.capturedAt()
  val serverPid: Option[Long] = asJava.serverPid().toScala
  val serverVersion: Option[TmuxVersion] = asJava.serverVersion().toScala
  val sessions: Vector[SessionInfo] =
    asJava.sessions().asScala.iterator.map(SessionInfo.fromJava).toVector
  val windows: Vector[WindowInfo] =
    asJava.windows().asScala.iterator.map(WindowInfo.fromJava).toVector
  val panes: Vector[PaneInfo] =
    asJava.panes().asScala.iterator.map(PaneInfo.fromJava).toVector
  val clients: Vector[ClientInfo] =
    asJava.clients().asScala.iterator.map(ClientInfo.fromJava).toVector

  def session(id: SessionId): Option[SessionInfo] = sessions.find(_.id == id)
  def session(name: String): Option[SessionInfo] = sessions.find(_.name == name)
  def window(context: WindowContext): Option[WindowInfo] =
    windows.find(_.context == context)
  def windowsOf(session: SessionId): Vector[WindowInfo] =
    windows.filter(_.context.session() == session)
  def panesOf(context: WindowContext): Vector[PaneInfo] =
    panes.filter(_.context == context)

  override def toString: String =
    s"Snapshot($capturedAt, ${sessions.size} sessions, ${windows.size} windows, ${panes.size} panes)"
}

object Snapshot {
  def fromJava(value: ServerSnapshot): Snapshot = new Snapshot(value)
}
