package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{ServerConfig, ServerEndpoint, WindowSpec}
import java.nio.file.Path
import io.github.libtmux.scaladsl.cats.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Create a window and read the updated session. */
object NewWindow extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val program = IO {
      require(args.length == 3, "expected: tmux-binary socket-path config-file")
      ServerConfig
        .builder()
        .binary(args(0))
        .endpoint(ServerEndpoint.socketPath(Path.of(args(1))))
        .configFile(Path.of(args(2)))
        .build()
    }.flatMap { config =>
      Server.resource[IO](config).use { server =>
        for {
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
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
