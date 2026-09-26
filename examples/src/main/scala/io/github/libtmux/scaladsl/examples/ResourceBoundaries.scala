package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{ExitCode, IO, IOApp, Outcome, Resource}
import _root_.cats.syntax.all._
import io.github.libtmux.{Server => JavaServer, ServerConfig, SessionSpec}
// Wildcard, not a named import: examples lives beside io.github.libtmux.scaladsl.cats, not inside
// it, so its generated extension methods need an explicit import.
import io.github.libtmux.scaladsl.cats.*
import scala.concurrent.duration._

/** Borrows a Java client without owning it, and cancels a dispatched wait —
  * cancellation only interrupts local Java work, so the session and the daemon
  * it belongs to both outlive it.
  */
object ResourceBoundaries extends IOApp {
  def run(arguments: List[String]): IO[ExitCode] =
    run(ExampleRuntime.config(arguments.toArray)).as(ExitCode.Success)

  def run(config: ServerConfig): IO[Unit] =
    Resource.fromAutoCloseable(IO.blocking(JavaServer.open(config))).use {
      java =>
        IO.blocking(
          require(java.isAlive(), "example requires an existing tmux server")
        ) *>
          Server.fromJava[IO](java, maxConcurrentCalls = 2).use { server =>
            Resource
              .make(
                server.newSession(
                  SessionSpec
                    .builder()
                    .named(ExampleRuntime.name("scala-resources"))
                    .running("cat")
                    .build()
                )
              )(_.kill())
              .use { session =>
                for {
                  _ <- IO(assert(server.asJava.eq(java)))
                  pane <- IO(session.windows.head.panes.head)
                  waiting <- pane.awaitText("never printed", 1.hour).start
                  _ <- IO.sleep(200.millis)
                  _ <- waiting.cancel
                  outcome <- waiting.join
                  _ <- IO(
                    assert(
                      outcome.isInstanceOf[Outcome.Canceled[IO, Throwable, ?]],
                      outcome
                    )
                  )
                  stillAlive <- server.isAlive()
                  _ <- IO(
                    assert(
                      stillAlive,
                      "cancelling the wait must not touch the daemon"
                    )
                  )
                } yield ()
              }
          } *> IO.blocking(assert(java.isAlive()))
    }
}
