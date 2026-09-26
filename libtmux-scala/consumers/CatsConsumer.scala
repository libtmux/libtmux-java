package io.github.libtmux.scaladsl.consumer

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import fs2.Stream
import io.github.libtmux.SessionSpec
// Wildcard, not a named import: consumers lives beside io.github.libtmux.scaladsl.cats, not
// inside it, so its generated extension methods need an explicit import.
import io.github.libtmux.scaladsl.cats.*
import io.github.libtmux.scaladsl.fixture.OwnedTmux

/** Installed-consumer smoke test for the Cats module: an owned resource-scoped
  * client, a session created and looked up, its windows refreshed through an
  * fs2 stream, the session killed, the scope torn down and proven closed, and
  * a borrowed client exercised without closing the fixture's daemon.
  */
object CatsConsumer {
  def main(arguments: Array[String]): Unit = {
    OwnedTmux.use { fixture =>
      val program = Server
        .resource[IO](fixture.config)
        .use { server =>
          val create = server.newSession(
            SessionSpec.builder().named("consumer-cats").running("cat").build()
          )
          for {
            before <- server.hasSession("consumer-cats")
            _ <- IO(assert(!before))
            session <- create
            found <- server.session("consumer-cats")
            windows <- Stream
              .emits(session.windows)
              .covary[IO]
              .evalMap(_.refresh())
              .compile
              .toVector
            _ <- IO {
              assert(windows.nonEmpty)
              assert(found.exists(_.equals(session)))
            }
            _ <- session.kill()
          } yield session
        }
        .flatMap { escaped =>
          escaped.refresh().attempt.flatMap(result => IO(assert(result.isLeft)))
        }
      program.unsafeRunSync()
      Server
        .fromJava[IO](fixture.server)
        .use { borrowed =>
          IO(
            assert(borrowed.asJava eq fixture.server)
          ) *> borrowed.sessions().void
        }
        .unsafeRunSync()
      assert(fixture.server.isAlive())
    }
    println(
      "CONSUMER_PASS cats cleanup=true java=" + Runtime.version().feature()
    )
  }
}
