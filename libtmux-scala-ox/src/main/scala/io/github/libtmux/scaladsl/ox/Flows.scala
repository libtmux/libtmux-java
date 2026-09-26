package io.github.libtmux.scaladsl.ox

import io.github.libtmux.control.{Delivery, EventSubscription}
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.streaming.Observation
import io.github.libtmux.snapshot.ServerMirror
import ox.flow.Flow

import java.time.Duration

/** An Ox `Flow` over a subscription or a live view, for a supervised scope. Ox
  * adds no new handle type (`io.github.libtmux.scaladsl`'s opaque handles work
  * unchanged inside a `fork`); what it needs from this project is the same
  * single-owner discipline the direct-style `Observation` and `LiveView`
  * already give a blocking caller, expressed as a cold `Flow` instead of an
  * `Iterator`.
  */
object Flows {

  /** One `Flow` element per step: an event this subscription kept, or a gap
    * naming what was lost. Claims the subscription exclusively for the `Flow`'s
    * lifetime, through [[Observation]]'s own guard, so a second concurrent
    * collection is rejected rather than splitting one gap-bearing sequence
    * between two readers.
    */
  def subscription[T](
      sub: EventSubscription[T],
      pollTimeout: Duration = Duration.ofSeconds(30)
  ): Flow[Delivery[T]] =
    Flow.usingEmit { emit =>
      val observation = Observation(sub)
      try
        observation.read(pollTimeout) { steps =>
          steps.foreach(emit.apply)
        }
      finally observation.close()
    }

  /** Every view newer than the one held when the flow starts, one per
    * notification.
    */
  def liveView(view: LiveView): Flow[ServerMirror.View] =
    Flow.usingEmit { emit =>
      view.snapshots.foreach(emit.apply)
    }
}
