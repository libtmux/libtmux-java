package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{SessionSpec, SplitSpec, WindowSpec}
import io.github.libtmux.scaladsl.cats.*
import io.github.libtmux.scaladsl.{Session as SessionFields}
import io.github.libtmux.scaladsl.query.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Select sessions with a typed name filter. */
object Query extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val program = Server.start[IO]().use { server =>
      for {
        workOne <- server.newSession(
          SessionSpec
            .builder()
            .named("work-one")
            .firstWindowNamed("editor")
            .running("/bin/cat")
            .build()
        )
        _ <- workOne.windows.head.split(
          SplitSpec.builder().toRight().running("/bin/cat").build()
        )
        _ <- workOne.newWindow(
          WindowSpec
            .builder()
            .named("logs")
            .running("/bin/cat")
            .detached()
            .build()
        )
        _ <- server.newSession(
          SessionSpec
            .builder()
            .named("work-two")
            .firstWindowNamed("editor")
            .running("/bin/cat")
            .build()
        )
        matches <- server.sessions(SessionFields.name.is("work-one"))
        _ <- IO.println("matches=" + matches.map(_.name).sorted.mkString(","))
      } yield ()
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
