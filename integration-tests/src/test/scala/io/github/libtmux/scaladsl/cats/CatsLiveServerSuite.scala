package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import munit.FunSuite
import scala.concurrent.duration._

/** The Cats module's live view: a `Signal` over Java's own `ServerMirror`,
  * whose background poll fiber's outcome is observed and surfaced through
  * [[LiveServer#failure]] rather than discarded.
  */
final class CatsLiveServerSuite extends FunSuite {

  test(
    "LiveServer's signal advances on a real window creation, and failure stays None while live"
  ) {
    OwnedTmux.use { fixture =>
      val program = Server.resource[IO](fixture.config).use { server =>
        for {
          session <- server.newSession("live-cats")
          result <- LiveServer.attach[IO](session).use { live =>
            for {
              initial <- live.signal.get
              _ <- IO.blocking(session.underlying.asJava.newWindow("second"))
              observed <- live.signal.discrete
                .filter(_.epoch() > initial.epoch())
                .take(1)
                .compile
                .lastOrError
                .timeoutTo(
                  20.seconds,
                  IO.raiseError(new AssertionError("no newer view within 20s"))
                )
              failure <- live.failure
            } yield (initial, observed, failure)
          }
        } yield result
      }
      val (initial, observed, failure) = program.unsafeRunSync()
      assert(
        observed.epoch() > initial.epoch(),
        s"expected a newer epoch, got ${observed.epoch()} after ${initial.epoch()}"
      )
      assertEquals(
        observed.snapshot().windows().size(),
        initial.snapshot().windows().size() + 1
      )
      assertEquals(failure, None)
    }
  }
}
