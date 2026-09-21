package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Resource}
import _root_.cats.syntax.all._
import fs2.Stream
import io.github.libtmux.control.EventSubscription
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.OptionConverters._

/** Pulls a bounded Java subscription with one active stream consumer. Overflow
  * drops the oldest buffered event. FS2 demand does not provide tmux
  * backpressure; reconcile a snapshot after loss when current state matters.
  */
final class Observation[F[_], A] private[cats] (
    private[scaladsl] val underlying: EventSubscription[A],
    private val ownerClosed: AtomicBoolean
)(implicit F: Async[F]) {
  private val closed = new AtomicBoolean(false)
  private val reading = new AtomicBoolean(false)

  /** Reads on an interruptible blocking worker. Canceling the stream releases
    * its consumer slot; releasing the observation discards its buffered events.
    * Deliberate Scala closure ends the stream; other closure has unknown cause.
    */
  def stream: Stream[F, A] = Stream
    .bracket(F.delay {
      if (!reading.compareAndSet(false, true))
        throw new IllegalStateException(
          "an observation already has an active consumer"
        )
    })(_ => F.delay(reading.set(false)))
    .flatMap { _ =>
      Stream
        .repeatEval(F.interruptible {
          underlying.next().toScala match {
            case None if !closed.get() && !ownerClosed.get() =>
              throw new Observation.UnknownCause()
            case value => value
          }
        })
        .unNoneTerminate
    }

  /** Reads the exact cumulative overflow count separately from event delivery.
    * The counter does not locate a gap within the delivered sequence.
    */
  def droppedCount: F[Long] = F.delay(underlying.droppedCount())

  /** Reports subscription closure, including after resource finalization. */
  def isClosed: F[Boolean] = F.delay(underlying.isClosed())

  private[cats] def close: F[Unit] =
    F.delay(closed.set(true)) *> F.blocking(underlying.close())
}

object Observation {

  /** Java ended the subscription without exposing the reason. */
  final class UnknownCause private[cats] ()
      extends IllegalStateException(
        "control observation ended without a deliberate Scala close; Java exposes no cause"
      )

  private[cats] def resource[F[_]: Async, A](
      acquire: => EventSubscription[A],
      ownerClosed: AtomicBoolean
  ): Resource[F, Observation[F, A]] =
    Resource.make(Async[F].delay(new Observation[F, A](acquire, ownerClosed)))(
      _.close
    )
}
