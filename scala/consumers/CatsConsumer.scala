package io.github.libtmux.scaladsl.consumer

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import fs2.Stream
import io.github.libtmux._
import io.github.libtmux.scaladsl.cats.{Server => ScalaServer}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import scala.collection.immutable.Vector

object CatsConsumer {
  def main(arguments: Array[String]): Unit = {
    OwnedTmux.use { fixture =>
      val program = ScalaServer.resource[IO](fixture.config).use { server =>
        val create = server.newSession(
          SessionSpec.builder().named("consumer-cats").running("cat").build()
        )
        for {
          before <- server.hasSession("consumer-cats")
          _ <- IO(assert(!before))
          session <- create
          found <- server.session("consumer-cats")
          windows <- Stream.emits(session.windows).covary[IO]
            .evalMap(_.refresh).compile.toVector
          _ <- IO {
            val native: Vector[io.github.libtmux.scaladsl.cats.Window[IO]] = windows
            assert(native.nonEmpty)
            assert(found.exists(_.info.id == session.info.id))
          }
          _ <- session.kill
        } yield session
      }.flatMap { escaped =>
        escaped.refresh.attempt.flatMap(result => IO(assert(result.isLeft)))
      }
      program.unsafeRunSync()
      ScalaServer.fromJava[IO](fixture.server).use { borrowed =>
        IO(assert(borrowed.asJava eq fixture.server)) *> borrowed.sessions.void
      }.unsafeRunSync()
      assert(fixture.server.isAlive())
    }
    println("CONSUMER_PASS cats cleanup=true java=" + Runtime.version().feature())
  }
}
