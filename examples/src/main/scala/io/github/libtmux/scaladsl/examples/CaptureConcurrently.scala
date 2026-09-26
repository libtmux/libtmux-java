package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{ExitCode, IO, IOApp, Resource}
import _root_.cats.syntax.all._
import fs2.Stream
import io.github.libtmux.{ServerConfig, SessionSpec, SplitSpec}
// Wildcard, not a named import: examples lives beside io.github.libtmux.scaladsl.cats, not inside
// it, so its generated extension methods need an explicit import.
import io.github.libtmux.scaladsl.cats.*
import scala.concurrent.duration._

/** Captures several panes concurrently, retaining each pane's own identity in
  * the result.
  */
object CaptureConcurrently extends IOApp {
  def run(arguments: List[String]): IO[ExitCode] =
    run(ExampleRuntime.config(arguments.toArray)).as(ExitCode.Success)

  def run(config: ServerConfig): IO[Unit] =
    Server.resource[IO](config).use { server =>
      server
        .isAlive()
        .flatMap(alive =>
          IO(require(alive, "example requires an existing tmux server"))
        ) *>
        Resource
          .make(
            server.newSession(
              SessionSpec
                .builder()
                .named(ExampleRuntime.name("scala-capture"))
                .running("/bin/sh")
                .build()
            )
          )(_.kill())
          .use { session =>
            for {
              first <- IO(session.windows.head.panes.head)
              second <- first.split(
                SplitSpec.builder().running("/bin/sh").build()
              )
              third <- second.split(
                SplitSpec.builder().running("/bin/sh").build()
              )
              panes = Vector(first, second, third)
              _ <- panes.traverse_(pane =>
                pane
                  .sendLine("printf 'capture-%s\\n' " + pane.info.id().value())
              )
              _ <- panes.traverse_(pane =>
                pane.awaitText("capture-" + pane.info.id().value(), 5.seconds)
              )
              captures <- Stream
                .emits(panes)
                .covary[IO]
                .parEvalMap(2)(pane =>
                  pane.capture().map(lines => (pane.info.id(), lines))
                )
                .compile
                .toVector
              _ <- IO {
                assert(
                  captures.map(_._1).map(_.value()).toSet == panes
                    .map(_.info.id().value())
                    .toSet
                )
                assert(captures.forall { case (id, lines) =>
                  lines.exists(_.contains("capture-" + id.value()))
                })
              }
            } yield ()
          }
    }
}
