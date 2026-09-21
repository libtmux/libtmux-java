package io.github.libtmux.scaladsl.examples

import _root_.cats.effect.{IO, Resource}
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all._
import io.github.libtmux.{
  Server => JavaServer,
  ServerConfig,
  SessionId,
  SessionSpec,
  WakeReason
}
import io.github.libtmux.scaladsl.cats.{Control, Server}
import scala.concurrent.duration._

/** Borrows Java, cancels dispatched work, and checks failed attachment cleanup.
  */
object ResourceBoundaries {
  def main(arguments: Array[String]): Unit =
    run(ExampleRuntime.config(arguments)).unsafeRunSync()

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
              )(_.kill)
              .use { session =>
                val entered = session.info.name + "-entered"
                val release = session.info.name + "-release"
                val finished = session.info.name + "-finished"
                val script = ExampleRuntime.shell(
                  config,
                  "wait-for",
                  "-S",
                  entered
                ) + "; " +
                  ExampleRuntime.shell(config, "wait-for", release) + "; " +
                  ExampleRuntime.shell(config, "wait-for", "-S", finished)
                val work = for {
                  _ <- IO(assert(server.asJava eq java))
                  original <- server.clients
                  failed <- Control
                    .attach[IO](
                      config,
                      new SessionId("$2147483647"),
                      ExampleRuntime.deadline
                    )
                    .use(_ => IO.unit)
                    .attempt
                  after <- server.clients
                  _ <- IO {
                    assert(failed.isLeft)
                    assert(after.map(_.info.name) == original.map(_.info.name))
                  }
                  pending <- server.batch
                    .add(
                      "set-option",
                      "-t",
                      session.info.id.value(),
                      "@partial",
                      "applied"
                    )
                    .add("run-shell", script)
                    .run
                    .start
                  dispatched <- server
                    .channel(entered)
                    .await(ExampleRuntime.deadline)
                  _ <- IO(assert(dispatched == WakeReason.SIGNALLED))
                  _ <- pending.cancel
                  outcome <- pending.join
                  applied <- session.options.get("@partial")
                  _ <- IO {
                    assert(outcome.isCanceled)
                    assert(applied.contains("applied"))
                  }
                  _ <- server.channel(release).signal
                  completed <- server
                    .channel(finished)
                    .await(ExampleRuntime.deadline)
                  _ <- IO(assert(completed == WakeReason.SIGNALLED))
                } yield ()
                work.guarantee(server.channel(release).signal)
              }
          } *> IO.blocking(assert(java.isAlive()))
    }
}
