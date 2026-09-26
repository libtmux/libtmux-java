package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{ExitCode, IO, IOApp, Resource}
import _root_.cats.syntax.all._
import io.github.libtmux.{ServerConfig, SessionSpec}
import scala.jdk.OptionConverters._
// Wildcard, not a named import: examples lives beside io.github.libtmux.scaladsl.cats, not inside
// it, so its generated extension methods need an explicit import.
import io.github.libtmux.scaladsl.cats.*
import scala.concurrent.duration._

/** Watches a session's live state as a `Signal`, reconciling it against a
  * window rename — the Cats module's live view is a thin `Signal` over Java's
  * own `ServerMirror`, never a hand-rolled resnapshot loop.
  */
object ObserveChanges extends IOApp {
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
                .named(ExampleRuntime.name("scala-events"))
                .firstWindowNamed("observed")
                .running("cat")
                .build()
            )
          )(_.kill())
          .use { session =>
            val window = session.windows.head
            LiveServer.attach[IO](session).use { live =>
              for {
                initial <- live.signal.get
                _ <- window.rename("renamed")
                observed <- live.signal.discrete
                  .filter(_.epoch() > initial.epoch())
                  .take(1)
                  .compile
                  .lastOrError
                  .timeoutTo(
                    ExampleRuntime.deadline.toMillis.millis,
                    IO.raiseError(new AssertionError("no newer view"))
                  )
                failure <- live.failure
                _ <- IO {
                  assert(
                    observed
                      .snapshot()
                      .window(window.info.context())
                      .toScala
                      .exists(_.name() == "renamed"),
                    "the live view did not observe the rename"
                  )
                  assert(
                    failure.isEmpty,
                    "the background poller must not have failed while still in use"
                  )
                }
              } yield ()
            }
          }
    }
}
