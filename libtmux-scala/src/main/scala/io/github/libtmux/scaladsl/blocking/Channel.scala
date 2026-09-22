package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{Channel => JavaChannel, WakeReason}
import java.time.Duration

/** A named signal. Shared waits reserve transport capacity for their release.
  */
final class Channel private[blocking] (
    private[scaladsl] val asJava: JavaChannel,
    server: Server
) {
  def unsafeJava: JavaChannel = asJava
  val name: String = asJava.name()
  def signal(): Unit = server.checked(asJava.signal())
  def await(timeout: Duration): WakeReason =
    server.checked(asJava.await(timeout))
  def awaitReservingCapacity(timeout: Duration): WakeReason =
    server.checked(asJava.awaitReservingCapacity(timeout))
  def drain(): Boolean = server.checked(asJava.drain())
  override def toString: String = asJava.toString
}
