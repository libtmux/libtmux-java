package io.github.libtmux.scaladsl

import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.live.LiveView
import munit.FunSuite
import scala.util.Using

/** Direct style's live view over a real server: wraps Java's `ServerMirror`
  * directly (no hand-rolled resnapshot-on-notification), rebuilding on every
  * announcement.
  */
final class LiveStateSuite extends FunSuite {

  test(
    "LiveView observes a real window creation as a newer, materially different snapshot"
  ) {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession("live-direct")
        Using.resource(LiveView.attach(session)) { live =>
          val initial = live.current
          val initialWindows = initial.snapshot.windows().size()

          session.newWindow("second")

          val next = live.snapshots.next()
          assert(
            next.epoch > initial.epoch,
            s"expected a newer epoch, got ${next.epoch} after ${initial.epoch}"
          )
          assertEquals(next.snapshot.windows().size(), initialWindows + 1)
          assert(!live.isEnded)
          assertEquals(live.cause, None)
        }
      }
    }
  }
}
