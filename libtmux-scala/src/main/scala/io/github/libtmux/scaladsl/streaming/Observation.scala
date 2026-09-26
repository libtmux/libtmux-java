package io.github.libtmux.scaladsl.streaming

import io.github.libtmux.control.{Delivery, EventSubscription}
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.OptionConverters._

/** A single-consumer guard over one Java subscription, for direct-style
  * reading.
  *
  * `EventSubscription` is already single-consumer by construction at the Java
  * layer — a second concurrent read is refused — but that guard is scoped to
  * one call; nothing stopped two direct `Iterator`s built from the same
  * subscription from interleaving reads across calls. This adds the same
  * scoped-CAS discipline the Cats module's own `Observation` already had, so a
  * `read` call is the unit of exclusive ownership here too, released when it
  * returns so a later sequential `read` stays legal.
  */
final class Observation[T] private (private val sub: EventSubscription[T])
    extends AutoCloseable {

  private val reading = new AtomicBoolean(false)

  /** Runs `use` over every step waiting or arriving within `pollTimeout` per
    * poll, until the subscription closes. Rejects a second, overlapping `read`
    * on this observation.
    *
    * @throws IllegalStateException
    *   if another `read` on this observation is already in progress
    */
  def read[A](pollTimeout: Duration)(use: Iterator[Delivery[T]] => A): A = {
    if (!reading.compareAndSet(false, true))
      throw new IllegalStateException(
        "an observation already has an active consumer"
      )
    try
      use(
        Iterator.unfold(())(_ =>
          sub.next(pollTimeout).toScala.map(step => (step, ()))
        )
      )
    finally reading.set(false)
  }

  /** The cumulative overflow count. A [[Delivery.Gap]] read by [[read]] says
    * where it sits.
    */
  def droppedCount: Long = sub.droppedCount()

  def close(): Unit = sub.close()
}

object Observation {
  def apply[T](sub: EventSubscription[T]): Observation[T] = new Observation(sub)
}
