package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{SessionSpec}
import io.github.libtmux.scaladsl.cats.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Create a session running cat. */
object NewSession extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val program = Server.start[IO]().use { server =>
      for {
        session <- server.newSession(
          SessionSpec
            .builder()
            .named("api-session")
            .running("/bin/cat")
            .build()
        )
        _ <- IO.println(s"created=${session.name}")
      } yield ()
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
