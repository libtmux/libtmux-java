package io.github.libtmux.scaladsl.ox

import io.github.libtmux.control.{Delivery, EventSubscription}
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.snapshot.ServerMirror
import ox.flow.Flow

/** An Ox `Flow` over a subscription or a live view, for a supervised scope. Ox
  * adds no new handle type (`io.github.libtmux.scaladsl`'s opaque handles work
  * unchanged inside a `fork`); what it needs from this project is the same
  * single-owner discipline the direct-style `Observation` and `LiveView`
  * already give a blocking caller, expressed as a cold `Flow` instead of an
  * `Iterator`.
  */
object Flows {

  /** One `Flow` element per step: an event this subscription kept, or a gap
    * naming what was lost. Collecting the flow takes the subscription for good,
    * through the Java subscription's own single-reader claim, and closes it
    * when the collection ends. A second collection is refused as it starts,
    * before it can disturb the first. The flow fails with the control client's
    * cause if the client ended the subscription.
    */
  def subscription[T](sub: EventSubscription[T]): Flow[Delivery[T]] =
    Flow.usingEmit { emit =>
      val steps = sub.stream()
      try steps.forEach(step => emit(step))
      finally steps.close()
    }

  /** The current view when the flow starts, then every newer one, one per
    * notification. Completes when the view is closed, and fails with its cause
    * when the anchor session or server has gone.
    */
  def liveView(view: LiveView): Flow[ServerMirror.View] =
    Flow.usingEmit { emit =>
      view.snapshots.foreach(emit.apply)
    }
}
