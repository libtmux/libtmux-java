package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{SessionSpec, SplitSpec, WindowSpec}
import io.github.libtmux.scaladsl.cats.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** List all panes, then the panes captured for one window. */
object ListPanes extends IOApp {
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
        panes <- server.panes()
        _ <- IO.println(s"panes=${panes.size}")
        found <- server.session("work-one")
        session <- IO.fromOption(found)(
          new IllegalStateException("missing work-one")
        )
        window <- IO.fromOption(
          session.windows.find(_.name.equals("editor"))
        )(
          new IllegalStateException("missing editor")
        )
        _ <- IO.println(s"window-panes=${window.panes.size}")
      } yield ()
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
