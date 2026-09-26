package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.Async
import io.github.libtmux.{Channel => JavaChannel, WakeReason}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

/** One of tmux's wait-for channels. `signal` is a real dispatch (tmux's own
  * `wait-for -S`), routed through `Execution#apply` like any other mutation;
  * `await` reserves this server's shared-wait capacity the way every other
  * handwritten `WAIT` operation does, since a Cats caller waiting and the
  * caller releasing it usually share this same bounded transport.
  */
final class Channel[F[_]] private[cats] (
    private[cats] val underlying: JavaChannel,
    private val execution: Execution[F]
)(implicit F: Async[F]) {

  def signal: F[Unit] = execution(underlying.signal())

  def await(timeout: FiniteDuration): F[WakeReason] =
    execution.waiting(underlying.awaitReservingCapacity(timeout.toJava))

  override def toString: String = underlying.toString
}
