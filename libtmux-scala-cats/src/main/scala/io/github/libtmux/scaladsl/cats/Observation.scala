package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.{Async, Resource}
import _root_.cats.syntax.all._
import fs2.Stream
import io.github.libtmux.control.{Delivery, EventSubscription}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.OptionConverters._

/** Pulls a bounded Java subscription with one active stream consumer. A full
  * buffer yields [[Delivery.Gap]] in this stream before the events that
  * remain. FS2 demand does not provide tmux backpressure. A subscription does
  * not reconnect: attach again and read a snapshot.
  */
final class Observation[F[_], A] private[cats] (
    private[scaladsl] val underlying: EventSubscription[A],
    private val ownerClosed: AtomicBoolean
)(implicit F: Async[F]) {
  private val closed = new AtomicBoolean(false)
  private val reading = new AtomicBoolean(false)

  /** Reads on an interruptible blocking worker. Canceling the stream releases
    * its consumer slot; releasing the observation discards its buffered events.
    * Deliberate closure ends the stream. A client that ended the subscription
    * fails it with that cause.
    */
  def stream: Stream[F, Delivery[A]] = Stream
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
              underlying.cause().toScala match {
                case Some(cause) => throw cause
                case None        => throw new Observation.UnknownCause()
              }
            case value => value
          }
        })
        .unNoneTerminate
    }

  /** The cumulative overflow count. A [[Delivery.Gap]] in [[#stream]] says where it sits. */
  def droppedCount: F[Long] = F.delay(underlying.droppedCount())

  /** Reports subscription closure, including after resource finalization. */
  def isClosed: F[Boolean] = F.delay(underlying.isClosed())

  private[cats] def close: F[Unit] =
    F.delay(closed.set(true)) *> F.blocking(underlying.close())
}

object Observation {

  /** The value inside an event delivery, and none for a gap. */
  def value[A](delivery: Delivery[A]): Option[A] = delivery match {
    case item: Delivery.Event[_] => Some(item.value().asInstanceOf[A])
    case _: Delivery.Gap[_]      => None
  }

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
