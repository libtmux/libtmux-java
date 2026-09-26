package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Resource}
import io.github.libtmux.{ServerConfig, SessionId}
import io.github.libtmux.scaladsl as direct
import io.github.libtmux.batch.OperationOutcome
import io.github.libtmux.control.{
  ControlClient,
  ControlEvent,
  ControlReply,
  PaneOutput
}
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

/** Owns an attached Java control client. Canceling an active request can end
  * this attachment and its queued requests and observations. Use separate
  * attachments for commands and observations that must survive that failure.
  */
final class Control[F[_]] private[cats] (
    private[scaladsl] val underlying: ControlClient,
    private val closed: AtomicBoolean,
    private val execution: Execution[F]
)(implicit F: Async[F]) {

  /** Reads one raw reply without claiming deferred command completion.
    * Cancellation does not prove that tmux rolled back a dispatched command.
    * The timeout starts after Scala admission; an effect timeout can bound the
    * whole call.
    */
  def acknowledge(
      argv: Seq[String],
      timeout: Duration = Duration.ofSeconds(30)
  ): F[Control.Ack] = execution {
    requireOpen()
    new Control.Ack(underlying.send(argv.asJava, timeout))
  }

  /** Whether this attachment's process is still running. */
  def isAlive: F[Boolean] = execution {
    requireOpen()
    underlying.isAlive()
  }

  /** Error text from this control process, at most 4096 bytes. */
  def standardError: F[String] = execution {
    requireOpen()
    underlying.standardError()
  }

  /** Whether `standardError` stopped before the process finished writing. */
  def standardErrorTruncated: F[Boolean] = execution {
    requireOpen()
    underlying.standardErrorTruncated()
  }

  /** Asks tmux to report `format` when it changes. A target that does not name
    * a pane or window watches the attached session.
    */
  def watch(name: String, target: String, format: String): F[Control.Ack] =
    execution {
      requireOpen()
      new Control.Ack(underlying.watch(name, target, format))
    }

  /** Removes a watch registered under `name`. */
  def unwatch(name: String): F[Control.Ack] = execution {
    requireOpen()
    new Control.Ack(underlying.unwatch(name))
  }

  /** Registers decoded text output before the returned resource body runs.
    * Terminal bytes and character boundaries are not preserved by Java.
    */
  def output(capacity: Int): Resource[F, Observation[F, PaneOutput]] =
    Observation.resource(
      {
        requireOpen()
        underlying.subscribeOutput(capacity)
      },
      closed
    )

  /** Registers Java's typed notifications, including unknown event kinds. */
  def events(capacity: Int): Resource[F, Observation[F, ControlEvent]] =
    Observation.resource(
      {
        requireOpen()
        underlying.subscribeEvents(capacity)
      },
      closed
    )

  private def requireOpen(): Unit =
    if (closed.get())
      throw new IllegalStateException("Scala control scope is closed")

  private[cats] def markClosed: F[Unit] = F.delay(closed.set(true))
}

object Control {

  /** Retains tmux's raw reply framing and line shape. */
  final class Ack private[cats] (private[scaladsl] val asJava: ControlReply) {
    def unsafeJava: ControlReply = asJava

    /** True for a %end reply. Deferred tmux work can still be running. */
    def accepted: Boolean = asJava.outcome().equals(OperationOutcome.COMPLETE)
    val lines: Vector[String] = asJava.lines().asScala.toVector
  }

  /** Attaches lazily to whatever server now answers this endpoint, without an
    * incarnation guard; prefer attaching a captured session. Bounds
    * simultaneous acknowledgement calls. Release stops their owned work before
    * detaching the client and preserves the tmux daemon.
    */
  def attachUnfenced[F[_]: Async](
      config: ServerConfig,
      session: SessionId,
      timeout: Duration = Duration.ofSeconds(30),
      maxConcurrentCalls: Int = 4
  ): Resource[F, Control[F]] =
    owned(
      maxConcurrentCalls,
      Async[F].interruptible(
        ControlClient.attachUnfenced(config, session, timeout)
      )
    )

  /** Attaches to the process the captured session named. */
  def attach[F[_]: Async](session: Session[F]): Resource[F, Control[F]] =
    attach(session, Duration.ofSeconds(30), 4)

  /** Attaches to the process the captured session named. */
  def attach[F[_]: Async](
      session: Session[F],
      timeout: Duration,
      maxConcurrentCalls: Int
  ): Resource[F, Control[F]] =
    attach(session.underlying, timeout, maxConcurrentCalls)

  /** Attaches to the process the captured session named. */
  def attach[F[_]: Async](
      session: direct.Session,
      timeout: Duration,
      maxConcurrentCalls: Int
  ): Resource[F, Control[F]] =
    owned(
      maxConcurrentCalls,
      Async[F].interruptible(
        session.asJava.server().control(session.asJava, timeout)
      )
    )

  private def owned[F[_]: Async](
      maxConcurrentCalls: Int,
      open: F[ControlClient]
  ): Resource[F, Control[F]] = {
    val F = Async[F]
    for {
      _ <- Resource.eval(
        F.delay(require(maxConcurrentCalls >= 1, "capacity must be positive"))
      )
      closed <- Resource.eval(F.delay(new AtomicBoolean(false)))
      underlying <- Resource.make(open)(client =>
        F.interruptible(client.close())
      )
      execution <- Execution.resource[F](maxConcurrentCalls)
      control <- Resource.make(
        F.pure(new Control[F](underlying, closed, execution))
      )(_.markClosed)
    } yield control
  }
}
