package io.github.libtmux.scaladsl

import io.github.libtmux.{Server => JavaServer, ServerConfig}
import io.github.libtmux.control.ControlClient
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

/** The Java client, opaque: every member beyond
  * [[java.lang.AutoCloseable#close]] is an extension, generated from the
  * operation catalog or handwritten where the catalog marks it `WAIT`, `STREAM`
  * or `LIFECYCLE`. Closing an owned client releases its transport, not tmux;
  * `close()` comes from the `AutoCloseable` bound with no forwarding of its
  * own, so `Using.resource(server) { ... }` works.
  */
opaque type Server <: AutoCloseable = JavaServer

object Server {

  /** Opens an owned client for `config`. */
  def open(config: ServerConfig): Server = JavaServer.open(config)

  /** Borrows a Java client this facade did not open; closing it closes that
    * client too.
    */
  def fromJava(java: JavaServer): Server = java

  private[scaladsl] def wrap(java: JavaServer): Server = fromJava(java)

  given CanEqual[Server, Server] = CanEqual.derived

  // Nested, not top-level: a top-level `extension (self: Server) def asJava` in this file would
  // share a name with Session/Window/Pane/Client's own top-level `asJava`, and Scala 3 requires
  // same-named top-level definitions to share one compilation unit ("the same group of toplevel
  // definitions"). Nested here, it is found through Server's own extension-method implicit scope
  // instead, with no cross-file collision.
  extension (self: Server) {

    /** The underlying Java client. Opaque wrapping is free, so this never
      * copies scope or state.
      */
    def asJava: JavaServer = self

    /** This server with every command given `timeout` rather than the default,
      * sharing this one's transport and scope. `LIFECYCLE`, handwritten: not a
      * per-operation forward, a derived scope.
      */
    def within(timeout: FiniteDuration): Server =
      Server.wrap(self.asJava.within(timeout.toJava))

    /** A control-mode client on `session`, refused by a tmux other than the one
      * that captured it. `LIFECYCLE`, handwritten: resource scoping, not a
      * per-operation forward.
      */
    def control(session: Session): ControlClient =
      self.asJava.control(session.asJava)

    /** As [[control(session:io\.github\.libtmux\.scaladsl\.Session)* control]],
      * with an explicit attach timeout.
      */
    def control(session: Session, timeout: FiniteDuration): ControlClient =
      self.asJava.control(session.asJava, timeout.toJava)
  }
}
