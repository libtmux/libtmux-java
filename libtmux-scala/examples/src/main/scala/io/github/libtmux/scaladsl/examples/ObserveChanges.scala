package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{ExitCode, IO, IOApp, Resource}
import _root_.cats.syntax.all._
import io.github.libtmux.{ServerConfig, SessionSpec}
import io.github.libtmux.control.Notification
import io.github.libtmux.scaladsl.cats.{Control, Observation, Server}
import scala.concurrent.duration._

/** Counts dropped notifications and reconciles current state with a snapshot.
  */
object ObserveChanges extends IOApp {
  def run(arguments: List[String]): IO[ExitCode] =
    run(ExampleRuntime.config(arguments.toArray)).as(ExitCode.Success)

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
                .named(ExampleRuntime.name("scala-events"))
                .firstWindowNamed("observed")
                .running("cat")
                .build()
            )
          )(_.kill)
          .use { session =>
            val window = session.windows.head
            window.options.set("automatic-rename", "off") *>
              Control
                .attach[IO](session, ExampleRuntime.deadline, 4)
                .use { control =>
                  val slowCapacity = 2
                  (control.events(slowCapacity), control.events(16)).tupled
                    .use { case (slow, witness) =>
                      val names =
                        Vector.tabulate(5)(index => "observed-" + index)
                      val minimumExpectedLoss =
                        (names.size - slowCapacity).toLong
                      for {
                        _ <- names.traverse_ { name =>
                          for {
                            arrived <- witness.stream
                              .map(Observation.value)
                              .unNone
                              .filter(_.notification() match {
                                case event: Notification.WindowRenamed =>
                                  event.window() == window.info.context
                                    .window() && event.name() == name
                                case _ => false
                              })
                              .take(1)
                              .compile
                              .lastOrError
                              .start
                            _ <- window.rename(name)
                            _ <- arrived.joinWithNever.timeout(
                              ExampleRuntime.deadline.toMillis.millis
                            )
                          } yield ()
                        }
                        dropped <- slow.droppedCount
                        current <- server.snapshot
                        _ <- IO {
                          assert(
                            dropped >= minimumExpectedLoss,
                            "dropped=" + dropped + ", expected at least " +
                              minimumExpectedLoss
                          )
                          assert(
                            current
                              .window(window.info.context)
                              .exists(_.name == names.last),
                            "current window did not retain " + names.last
                          )
                        }
                      } yield ()
                    }
                }
          }
    }
}
