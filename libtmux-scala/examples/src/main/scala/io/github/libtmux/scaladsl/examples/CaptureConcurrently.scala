package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{IO, Resource}
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import fs2.Stream
import io.github.libtmux.{Pane_, ServerConfig, SessionSpec, SplitSpec}
import io.github.libtmux.scaladsl.cats.{Control, Observation, Server}
import scala.concurrent.duration._

/** Captures at most two panes at once while retaining input order and context.
  */
object CaptureConcurrently {
  def main(arguments: Array[String]): Unit =
    run(ExampleRuntime.config(arguments)).unsafeRunSync()

  def run(config: ServerConfig): IO[Unit] =
    Server.resource[IO](config).use { server =>
      server.isAlive.flatMap(alive =>
        IO(require(alive, "example requires an existing tmux server"))
      ) *>
        Resource
          .make(
            server.newSession(
              SessionSpec
                .builder()
                .named(ExampleRuntime.name("scala-capture"))
                .running("cat")
                .build()
            )
          )(_.kill)
          .use { session =>
            for {
              second <- session.windows.head.panes.head
                .split(SplitSpec.builder().running("cat").build())
              _ <- second.split(SplitSpec.builder().running("cat").build())
              acquired <- server.panes
              panes = acquired.filter(
                _.info.context.session() == session.info.id
              )
              _ <- IO {
                assert(panes.size == 3)
                val expression = Pane_.active().isTrue()
                assert(
                  panes.filter(pane => expression.test(pane.asJava)) == panes
                    .filter(_.info.active)
                )
              }
              _ <- Control
                .attach[IO](session, ExampleRuntime.deadline, 4)
                .use { control =>
                  control.output(32).use { output =>
                    for {
                      _ <- panes.traverse_ { pane =>
                        val marker = "capture-" + pane.info.id.value()
                        val observed = output.stream
                          .map(Observation.value)
                          .unNone
                          .filter(_.pane() == pane.info.id)
                          .map(_.data())
                          .scan("")((text, chunk) =>
                            (text + chunk).takeRight(256)
                          )
                          .filter(_.contains(marker))
                          .take(1)
                          .compile
                          .lastOrError
                        (
                          pane.sendLine(marker),
                          observed.timeout(
                            ExampleRuntime.deadline.toMillis.millis
                          )
                        ).parTupled.void
                      }
                      captures <- Stream
                        .emits(panes)
                        .covary[IO]
                        .parEvalMap(2) { pane =>
                          pane.capture.map(lines =>
                            (pane.info.context, pane.info.id, lines)
                          )
                        }
                        .compile
                        .toVector
                      _ <- IO {
                        assert(
                          captures.map(value => (value._1, value._2)) == panes
                            .map(pane => (pane.info.context, pane.info.id))
                        )
                        assert(captures.forall { case (_, id, lines) =>
                          lines.exists(_.contains("capture-" + id.value()))
                        })
                      }
                    } yield ()
                  }
                }
            } yield ()
          }
    }
}
