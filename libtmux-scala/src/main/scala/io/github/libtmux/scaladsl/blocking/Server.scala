package io.github.libtmux.scaladsl.blocking

import io.github.libtmux.{
  Pane => JavaPane,
  PaneId,
  Server => JavaServer,
  Session => JavaSession,
  ServerConfig,
  ServerIdentity,
  SessionId,
  SessionSpec,
  TmuxVersion,
  Window => JavaWindow,
  WindowId
}
import io.github.libtmux.query.FilterExpr
import io.github.libtmux.scaladsl.{CommandResult, Snapshot}
import io.github.libtmux.snapshot.WindowContext
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** Immediate operations. Closing an owned client releases its transport, not
  * tmux.
  */
final class Server private (
    private[scaladsl] val asJava: JavaServer,
    owned: Boolean
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)

  /** The Java client. Closing an owned facade closes it; closing a borrowed one
    * leaves it open.
    */
  def unsafeJava: JavaServer = asJava

  private[blocking] def checked[A](operation: => A): A = {
    if (closed.get())
      throw new IllegalStateException("Scala server client is closed")
    operation
  }

  def sessions(): Vector[Session] = checked {
    asJava.sessions().asScala.iterator.map(new Session(_, this)).toVector
  }

  /** The sessions this expression matches; a safe one is sent to tmux as `-f`.
    */
  def sessions(expression: FilterExpr[JavaSession]): Vector[Session] = checked {
    asJava
      .sessions(expression)
      .asScala
      .iterator
      .map(new Session(_, this))
      .toVector
  }

  def panes(): Vector[Pane] = checked {
    asJava.panes().asScala.iterator.map(new Pane(_, this)).toVector
  }
  def panes(expression: FilterExpr[JavaPane]): Vector[Pane] = checked {
    asJava.panes(expression).asScala.iterator.map(new Pane(_, this)).toVector
  }

  def windows(): Vector[Window] = checked {
    asJava.windows().asScala.iterator.map(new Window(_, this)).toVector
  }
  def windows(expression: FilterExpr[JavaWindow]): Vector[Window] = checked {
    asJava
      .windows(expression)
      .asScala
      .iterator
      .map(new Window(_, this))
      .toVector
  }
  def windows(id: WindowId): Vector[Window] = checked {
    asJava.windows(id).asScala.iterator.map(new Window(_, this)).toVector
  }
  def clients(): Vector[Client] = checked {
    asJava.clients().asScala.iterator.map(new Client(_, this)).toVector
  }
  def snapshot(): Snapshot = checked(Snapshot.fromJava(asJava.snapshot()))
  def session(name: String): Option[Session] = checked(
    asJava.session(name).toScala.map(new Session(_, this))
  )
  def session(id: SessionId): Option[Session] = checked(
    asJava.session(id).toScala.map(new Session(_, this))
  )
  def pane(id: PaneId): Option[Pane] = checked(
    asJava.pane(id).toScala.map(new Pane(_, this))
  )
  def window(context: WindowContext): Option[Window] = checked(
    asJava.window(context).toScala.map(new Window(_, this))
  )
  def newSession(name: String): Session = checked(
    new Session(asJava.newSession(name), this)
  )
  def newSession(spec: SessionSpec): Session = checked(
    new Session(asJava.newSession(spec), this)
  )
  def hasSession(name: String): Boolean = checked(asJava.hasSession(name))
  def killSession(name: String): Unit = checked(asJava.killSession(name))
  def killServer(): Unit = checked(asJava.killServer())
  def killServer(timeout: Duration): Unit = checked(asJava.killServer(timeout))
  def lock(): Unit = checked(asJava.lock())
  def isAlive(): Boolean = checked(asJava.isAlive())
  def isAlive(timeout: Duration): Boolean = checked(asJava.isAlive(timeout))
  def version(): TmuxVersion = checked(asJava.version())
  def expand(format: String): String = checked(asJava.expand(format))
  def runShell(command: String): Unit = checked(asJava.shell().run(command))
  def runShellCapturing(command: String): Vector[String] = checked(
    asJava.shell().capturing(command).asScala.toVector
  )
  def sourceFile(file: Path): Unit = checked(asJava.sourceFile(file))
  def batch(): Batch = checked(new Batch(asJava.batch(), this))
  def chain(): CommandChain = checked(new CommandChain(asJava.chain(), this))
  def cmd(argv: Seq[String]): CommandResult = checked(
    CommandResult.fromJava(asJava.cmd(argv.asJava))
  )
  def cmd(argv: Seq[String], timeout: Duration): CommandResult = checked(
    CommandResult.fromJava(asJava.cmd(argv.asJava, timeout))
  )
  def cmd(command: String, arguments: String*): CommandResult = cmd(
    command +: arguments
  )

  def options: Options = new Options(asJava.options(), this)
  def globalOptions: Options = new Options(asJava.globalOptions(), this)
  def environment: Environment = new Environment(asJava.environment(), this)
  def hooks: Hooks = new Hooks(asJava.hooks(), this)
  def buffers: Buffers = new Buffers(asJava.buffers(), this)
  def channel(name: String): Channel = new Channel(asJava.channel(name), this)
  def config: ServerConfig = asJava.config()

  /** Endpoint and realm identity. This value does not contain a captured PID.
    */
  def identity: ServerIdentity = asJava.identity()

  override def close(): Unit = {
    if (closed.compareAndSet(false, true) && owned) asJava.close()
  }
}

object Server {
  def open(config: ServerConfig): Server =
    new Server(JavaServer.open(config), owned = true)

  /** Borrows the Java client; closing this facade does not close that client.
    */
  def fromJava(server: JavaServer): Server = new Server(server, owned = false)
}
