package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Resource}
import _root_.cats.effect.kernel.Poll
import _root_.cats.effect.std.{Semaphore, Supervisor}
import _root_.cats.syntax.all._
import java.util.concurrent.atomic.AtomicBoolean

/** Admits, runs, and cancels blocking Java calls.
  *
  * Cancellation stays `Canceled`. A call already handed to tmux may still have
  * run; the Java transport reports that as `DispatchOutcome.UNKNOWN` to the
  * `OperationObserver` in the server's configuration.
  */
private[cats] final class Execution[F[_]] private[cats] (
    total: Semaphore[F],
    waiters: Option[Semaphore[F]],
    supervisor: Supervisor[F],
    closed: AtomicBoolean
)(implicit F: Async[F]) {
  def apply[A](operation: => A): F[A] =
    supervise(total.permit.use(_ => F.interruptible(operation)))

  def waiting[A](operation: => A): F[A] = waiters match {
    case None =>
      F.raiseError(
        new IllegalStateException(
          "shared waits require capacity of at least two calls"
        )
      )
    case Some(permits) =>
      // A queued waiter must not consume the permit needed for its signal.
      supervise(
        permits.permit.use(_ =>
          total.permit.use(_ => F.interruptible(operation))
        )
      )
  }

  // The supervisor cancels a call when the scope closes. A caller that can be
  // canceled is canceled with it; one that masked cancellation cannot be, so it
  // gets the closed scope as an error instead of waiting forever.
  private def supervise[A](operation: F[A]): F[A] =
    F.uncancelable { poll =>
      refuseClosed *> supervisor.supervise(operation).flatMap { fiber =>
        F.onCancel(poll(fiber.join), fiber.cancel)
          .flatMap(_.embed(asCancel(poll)))
      }
    }

  private def asCancel[A](poll: Poll[F]): F[A] =
    poll(F.canceled) *> F.raiseError(closedScope)

  private def refuseClosed: F[Unit] =
    F.delay {
      if (closed.get()) throw closedScope
    }

  private def closedScope =
    new IllegalStateException("Scala client scope is closed")
}

private[cats] object Execution {
  def resource[F[_]: Async](capacity: Int): Resource[F, Execution[F]] = {
    val F = Async[F]
    for {
      _ <- Resource.eval(
        F.delay(require(capacity >= 1, "capacity must be positive"))
      )
      total <- Resource.eval(Semaphore[F](capacity.toLong))
      waiters <- Resource.eval(
        if (capacity == 1) F.pure(Option.empty[Semaphore[F]])
        else Semaphore[F](capacity.toLong - 1).map(Some(_))
      )
      supervisor <- Supervisor[F](await = false)
      closed <- Resource.make(F.delay(new AtomicBoolean(false)))(flag =>
        F.delay(flag.set(true))
      )
    } yield new Execution(total, waiters, supervisor, closed)
  }
}
