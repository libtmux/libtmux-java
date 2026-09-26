package io.github.libtmux.scaladsl

import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.live.LiveView
import java.time.Duration
import munit.FunSuite
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Using

/** Direct style's live view over a real server: wraps Java's `ServerMirror`
  * directly (no hand-rolled resnapshot-on-notification), rebuilding on every
  * announcement.
  */
final class LiveStateSuite extends FunSuite {

  private given ExecutionContext = ExecutionContext.global

  /** A view the mirror has already published is still delivered: waiting first
    * makes the change land before the iterator exists.
    */
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
          assert(
            live.awaitNewer(initial.epoch, Duration.ofSeconds(10)).isDefined,
            "the mirror never published the new window"
          )

          val next = Await.result(Future(live.snapshots.next()), 10.seconds)
          assert(
            next.epoch > initial.epoch,
            s"expected a newer epoch, got ${next.epoch} after ${initial.epoch}"
          )
          assertEquals(next.snapshot.windows().size(), initialWindows + 1)

          val after = Await.result(
            Future(live.snapshotsAfter(initial.epoch).next()),
            10.seconds
          )
          assertEquals(after.epoch, next.epoch)
          assert(!live.isEnded)
          assertEquals(live.cause, None)
        }
      }
    }
  }

  /** A mirror whose anchor has gone fails the iteration with the reason, where
    * a closed one ends it: a caller could not otherwise tell a dead session
    * from a quiet one.
    */
  test(
    "LiveView snapshots fail with the cause once the anchor session is gone"
  ) {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        server.newSession("live-keep")
        val anchor = server.newSession("live-anchor")
        Using.resource(LiveView.attach(anchor)) { live =>
          val views = live.snapshotsAfter(live.current.epoch)
          anchor.kill()

          val failed = intercept[Throwable](
            Await.result(
              Future(while views.hasNext do views.next()),
              30.seconds
            )
          )
          assert(live.isEnded)
          assertEquals(live.cause, Some(failed))
        }
      }
    }
  }
}
