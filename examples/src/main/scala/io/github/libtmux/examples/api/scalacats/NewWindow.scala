package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{SessionSpec, SplitSpec, WindowSpec}
import io.github.libtmux.scaladsl.cats.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Create a window and read the updated session. */
object NewWindow extends IOApp {
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
        found <- server.session("work-one")
        session <- IO.fromOption(found)(
          new IllegalStateException("missing work-one")
        )
        window <- session.newWindow(
          WindowSpec.builder().named("api-window").running("/bin/cat").build()
        )
        _ <- IO.println(s"created=${window.name}")
        updated <- server.session("work-one")
        refreshed <- IO.fromOption(updated)(
          new IllegalStateException("missing work-one")
        )
        _ <- IO.println(
          "windows=" + refreshed.windows.map(_.name).sorted.mkString(",")
        )
      } yield ()
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
