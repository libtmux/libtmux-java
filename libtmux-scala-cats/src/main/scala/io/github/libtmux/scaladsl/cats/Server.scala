package io.github.libtmux.scaladsl.cats

import io.github.libtmux.exception.{
  LibTmuxException,
  ServerUnavailableException
}
import _root_.cats.effect.{Async, Resource}
import _root_.cats.syntax.all._
import io.github.libtmux.{
  Pane => JavaPane,
  PaneId,
  Server => JavaServer,
  ServerConfig,
  ServerIdentity,
  Session => JavaSession,
  SessionId,
  SessionSpec,
  TmuxVersion,
  Window => JavaWindow,
  WindowId
}
import io.github.libtmux.query.FilterExpr
import io.github.libtmux.scaladsl.{CommandResult, Snapshot, blocking}
import io.github.libtmux.snapshot.WindowContext
import java.nio.file.Path
import java.time.Duration
import scala.collection.immutable.VectorMap

/** Lazy operations scoped by a Resource. Cancellation interrupts local Java
  * work; it does not prove that tmux rolled back a dispatched command.
  */
final class Server[F[_]] private[cats] (
    private[cats] val underlying: blocking.Server,
    private[cats] val execution: Execution[F]
)(implicit F: Async[F]) {

  /** The Java client. Releasing a `resource` closes it; releasing a `fromJava`
    * borrow leaves it open.
    */
  def unsafeJava: JavaServer = asJava
  private[scaladsl] val asJava: JavaServer = underlying.asJava
  def config: ServerConfig = underlying.config
  def identity: ServerIdentity = underlying.identity
  private[cats] def session(value: blocking.Session): Session[F] =
    new Session(value, this)
  private[cats] def window(value: blocking.Window): Window[F] =
    new Window(value, this)
  private[cats] def pane(value: blocking.Pane): Pane[F] = new Pane(value, this)
  private[cats] def client(value: blocking.Client): Client[F] =
    new Client(value, this)

  def sessions: F[Vector[Session[F]]] =
    execution(underlying.sessions()).map(_.map(session))

  /** The sessions `expression` matches, read as the blocking facade reads them:
    * a safe expression is sent to tmux, and what comes back is tested again.
    */
  def sessions(expression: FilterExpr[JavaSession]): F[Vector[Session[F]]] =
    execution(underlying.sessions(expression)).map(_.map(session))
  def windows: F[Vector[Window[F]]] =
    execution(underlying.windows()).map(_.map(window))
  def windows(expression: FilterExpr[JavaWindow]): F[Vector[Window[F]]] =
    execution(underlying.windows(expression)).map(_.map(window))
  def windows(id: WindowId): F[Vector[Window[F]]] =
    execution(underlying.windows(id)).map(_.map(window))
  def panes: F[Vector[Pane[F]]] = execution(underlying.panes()).map(_.map(pane))
  def panes(expression: FilterExpr[JavaPane]): F[Vector[Pane[F]]] =
    execution(underlying.panes(expression)).map(_.map(pane))
  def attachedSessions: F[Vector[Session[F]]] =
    execution(underlying.attachedSessions()).map(_.map(session))
  def clients: F[Vector[Client[F]]] =
    execution(underlying.clients()).map(_.map(client))
  def snapshot: F[Snapshot] = execution(underlying.snapshot())
  def session(name: String): F[Option[Session[F]]] =
    execution(underlying.session(name)).map(_.map(session))
  def session(id: SessionId): F[Option[Session[F]]] =
    execution(underlying.session(id)).map(_.map(session))
  def pane(id: PaneId): F[Option[Pane[F]]] =
    execution(underlying.pane(id)).map(_.map(pane))
  def window(context: WindowContext): F[Option[Window[F]]] =
    execution(underlying.window(context)).map(_.map(window))
  def newSession(name: String): F[Session[F]] =
    execution(underlying.newSession(name)).map(session)
  def newSession(spec: SessionSpec): F[Session[F]] =
    execution(underlying.newSession(spec)).map(session)
  def hasSession(name: String): F[Boolean] = execution(
    underlying.hasSession(name)
  )
  def killSession(name: String): F[Unit] = execution(
    underlying.killSession(name)
  )
  def killServer: F[Unit] = execution(underlying.killServer())

  /** As `killServer`, waiting at most `timeout` for the daemon to exit. */
  def killServer(timeout: Duration): F[Unit] =
    execution(underlying.killServer(timeout))
  def lock: F[Unit] = execution(underlying.lock())
  def isAlive: F[Boolean] = execution(underlying.isAlive())
  def isAlive(timeout: Duration): F[Boolean] =
    execution(underlying.isAlive(timeout))

  /** Fails with `ServerUnavailableException` when no daemon answers. */
  def requireAlive: F[Unit] = execution(underlying.requireAlive())
  def version: F[TmuxVersion] = execution(underlying.version())
  def expand(format: String): F[String] = execution(underlying.expand(format))
  def runShell(command: String): F[Unit] = execution(
    underlying.runShell(command)
  )
  def runShellCapturing(command: String): F[Vector[String]] = execution(
    underlying.runShellCapturing(command)
  )
  def sourceFile(file: Path): F[Unit] = execution(underlying.sourceFile(file))
  def batch: Batch[F] = new Batch(() => underlying.batch(), execution)
  def chain: CommandChain[F] =
    new CommandChain(() => underlying.chain(), execution)
  def cmd(argv: Seq[String]): F[CommandResult] = execution(underlying.cmd(argv))
  def cmd(argv: Seq[String], timeout: Duration): F[CommandResult] = execution(
    underlying.cmd(argv, timeout)
  )
  def cmd(command: String, arguments: String*): F[CommandResult] = cmd(
    command +: arguments
  )

  /** As `cmd`, failing with `LibTmuxException` when tmux exits nonzero. */
  def run(argv: Seq[String]): F[CommandResult] = execution(underlying.run(argv))
  def run(command: String, arguments: String*): F[CommandResult] = run(
    command +: arguments
  )
  def variables(names: Seq[String]): F[VectorMap[String, String]] =
    execution(underlying.variables(names))
  def paneFields(
      names: Seq[String]
  ): F[VectorMap[PaneId, VectorMap[String, String]]] =
    execution(underlying.paneFields(names))
  def setMouseEnabled(enabled: Boolean): F[Unit] =
    execution(underlying.setMouseEnabled(enabled))

  /** This server with every command given `timeout`, sharing this one's calls
    * and scope.
    */
  def within(timeout: Duration): Server[F] =
    new Server(underlying.within(timeout), execution)
  def options: Options[F] = new Options(underlying.options, execution)
  def globalOptions: Options[F] =
    new Options(underlying.globalOptions, execution)
  def environment: Environment[F] =
    new Environment(underlying.environment, execution)
  def hooks: Hooks[F] = new Hooks(underlying.hooks, execution)
  def buffers: Buffers[F] = new Buffers(underlying.buffers, execution)
  def channel(name: String): Channel[F] =
    new Channel(underlying.channel(name), execution)
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
      server <- Resource.make(F.blocking(blocking.Server.open(config)))(
        server => F.blocking(server.close())
      )
      execution <- Execution.resource[F](maxConcurrentCalls)
    } yield new Server(server, execution)
  }

  /** Borrows Java without closing it. The bound covers this facade only;
    * transport capacity and interruption remain the owner's responsibility.
    */
  def fromJava[F[_]: Async](
      java: JavaServer,
      maxConcurrentCalls: Int = 4
  ): Resource[F, Server[F]] = {
    val F = Async[F]
    for {
      server <- Resource.make(F.delay(blocking.Server.fromJava(java)))(server =>
        F.blocking(server.close())
      )
      execution <- Execution.resource[F](maxConcurrentCalls)
    } yield new Server(server, execution)
  }
}
