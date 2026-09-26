package io.github.libtmux.scaladsl.ox

import io.github.libtmux.control.{ControlEvent, Delivery, Notification}
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.snapshot.ServerMirror
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import _root_.ox.{fork, supervised}
import scala.util.Using

/** The Ox flows against a real tmux. Each change is made after the flow is
  * known to be listening, or lands in a subscription created first, so nothing
  * here depends on which side of a race it lost.
  */
final class OxFlowsSuite extends FunSuite {

  private def renamedTo(name: String)(step: Delivery[ControlEvent]): Boolean =
    Delivery.kept(step).notification() match {
      case renamed: Notification.SessionRenamed => renamed.name() == name
      case _                                    => false
    }

  test(
    "a subscription flow delivers what arrived before it was collected, then stops at take"
  ) {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession("ox-events")
        Using.resource(server.control(session)) { control =>
          val subscription = control.subscribeEvents(16)
          session.rename("ox-renamed")
          val found = Flows
            .subscription(subscription)
            .filter(renamedTo("ox-renamed"))
            .take(1)
            .runToList()
          assertEquals(found.size, 1)
        }
      }
    }
  }

  test(
    "a second collection of one subscription is refused while the first holds it"
  ) {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession("ox-claim")
        Using.resource(server.control(session)) { control =>
          val subscription = control.subscribeEvents(16)
          supervised {
            val reading = new CountDownLatch(1)
            val first = fork {
              Flows
                .subscription(subscription)
                .tap(_ => reading.countDown())
                .filter(renamedTo("ox-second"))
                .take(1)
                .runToList()
            }
            session.rename("ox-first")
            assert(
              reading.await(10, TimeUnit.SECONDS),
              "the first collection never read"
            )
            intercept[IllegalStateException](
              Flows.subscription(subscription).take(1).runToList()
            )
            session.rename("ox-second")
            assertEquals(first.join().size, 1)
          }
        }
      }
    }
  }

  test(
    "a live-view flow starts from the current view, then sees a change made after it began"
  ) {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession("ox-live")
        Using.resource(LiveView.attach(session)) { live =>
          val initial = live.current
          val windows = initial.snapshot.windows().size()
          val first = new AtomicReference[ServerMirror.View]()
          val started = new CountDownLatch(1)
          supervised {
            val grown = fork {
              Flows
                .liveView(live)
                .tap { view =>
                  first.compareAndSet(null, view)
                  started.countDown()
                }
                .filter(_.snapshot.windows().size() == windows + 1)
                .take(1)
                .runToList()
            }
            assert(
              started.await(10, TimeUnit.SECONDS),
              "the flow emitted nothing"
            )
            session.newWindow("second")
            val List(after) = grown.join(): @unchecked
            assertEquals(
              first.get().epoch,
              initial.epoch,
              "the flow did not start from the current view"
            )
            assert(
              after.epoch > first.get().epoch,
              "the change did not arrive as a newer view"
            )
          }
        }
      }
    }
  }
}
