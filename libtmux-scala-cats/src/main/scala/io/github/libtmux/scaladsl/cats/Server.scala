package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Resource}
import io.github.libtmux.{Server => JavaServer, ServerConfig}
import io.github.libtmux.scaladsl as direct

/** Lazy operations scoped by a `Resource`. Cancellation interrupts local Java
  * work; it does not prove that tmux rolled back a dispatched command. Every
  * acquisition wraps its Java call in `F.interruptible` — including this
  * class's own construction step, for consistency across every acquisition
  * site, though nothing in `ProcessTransport`'s constructor actually blocks:
  * the process-reclaim guarantee that makes cancellation safe applies to
  * dispatching a command and to opening a control client, not to this step.
  */
final class Server[F[_]] private[cats] (
    private[cats] val underlying: direct.Server,
    private[cats] val execution: Execution[F]
)(implicit F: Async[F]) {

  private[scaladsl] val asJava: JavaServer = underlying.asJava

  private[cats] def session(value: direct.Session): Session[F] =
    new Session(value, this)
  private[cats] def window(value: direct.Window): Window[F] =
    new Window(value, this)
  private[cats] def pane(value: direct.Pane): Pane[F] = new Pane(value, this)
  private[cats] def client(value: direct.Client): Client[F] =
    new Client(value, this)
}

object Server {

  /** Owns a Java client, with one to four simultaneous facade calls. Release
    * cancels queued and running operations before closing the client.
    */
  def resource[F[_]: Async](
      config: ServerConfig,
      maxConcurrentCalls: Int = 4
  ): Resource[F, Server[F]] = {
    val F = Async[F]
    for {
      _ <- Resource.eval(
        F.delay(
          require(
            maxConcurrentCalls >= 1 && maxConcurrentCalls <= 4,
            "owned client capacity must be between one and four calls"
          )
        )
      )
      server <- Resource.make(F.interruptible(direct.Server.open(config)))(
        server => F.interruptible(server.close())
      )
      execution <- Execution.resource[F](maxConcurrentCalls)
    } yield new Server(server, execution)
  }

  /** Borrows Java without closing it. The bound covers this facade only;
    * transport capacity and interruption remain the owner's responsibility.
    *
    * `direct.Server` is opaque, so a borrowed handle and an owned one are the
    * same runtime object. Release here tears down only this scope's own
    * `Execution` (its semaphores and supervised fibers); it never calls
    * `close()` on the borrowed handle, which is `resource`'s job alone.
    */
  def fromJava[F[_]: Async](
      java: JavaServer,
      maxConcurrentCalls: Int = 4
  ): Resource[F, Server[F]] =
    for {
      server <- Resource.eval(
        Async[F].interruptible(direct.Server.fromJava(java))
      )
      execution <- Execution.resource[F](maxConcurrentCalls)
    } yield new Server(server, execution)
}
