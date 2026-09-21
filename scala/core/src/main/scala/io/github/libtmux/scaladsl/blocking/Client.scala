package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{Client => JavaClient}
import io.github.libtmux.scaladsl.ClientInfo
import scala.jdk.OptionConverters._

final case class ClientAttachment(
    session: Session,
    activeWindow: Window,
    activePane: Pane
)

/** A captured attached client. Mutation and refresh are explicit operations. */
final class Client private[blocking] (
    val asJava: JavaClient,
    val server: Server
) {
  val info: ClientInfo =
    ClientInfo(asJava.name(), asJava.session().toScala.map(_.id()))
  def session: Option[Session] =
    asJava.session().toScala.map(new Session(_, server))
  def attachment: Option[ClientAttachment] =
    asJava.attachment().toScala.map { value =>
      ClientAttachment(
        new Session(value.session(), server),
        new Window(value.activeWindow(), server),
        new Pane(value.activePane(), server)
      )
    }
  def refresh(): Option[Client] =
    server.checked(asJava.refresh().toScala.map(new Client(_, server)))
  def fetchAttachment(): Option[ClientAttachment] =
    server.checked(refresh().flatMap(_.attachment))
  def detach(): Unit = server.checked(asJava.detach())
  def detachOthers(): Unit = server.checked(asJava.detachOthers())
  def switchTo(session: Session): Unit =
    server.checked(asJava.switchTo(session.asJava))
  def redraw(): Unit = server.checked(asJava.redraw())

  override def equals(other: Any): Boolean = other match {
    case that: Client => asJava == that.asJava
    case _            => false
  }
  override def hashCode(): Int = asJava.hashCode()
  override def toString: String = asJava.toString
}

object Client {
  def fromJava(client: JavaClient): Client =
    new Client(client, Server.fromJava(client.server()))
}
