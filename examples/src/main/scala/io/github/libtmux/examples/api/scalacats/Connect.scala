package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.scaladsl.cats.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Connect to a private tmux server. */
object Connect extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val program = Server.start[IO]().use { server =>
      for {
        alive <- server.isAlive()
        _ <- IO.println(s"connected=$alive")
      } yield ()
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
