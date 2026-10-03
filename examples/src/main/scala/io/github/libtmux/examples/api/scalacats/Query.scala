package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{ServerConfig, ServerEndpoint}
import java.nio.file.Path
import io.github.libtmux.scaladsl.cats.*
import io.github.libtmux.scaladsl.{Session as SessionFields}
import io.github.libtmux.scaladsl.query.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Select sessions with a typed name filter. */
object Query extends IOApp {
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
          matches <- server.sessions(SessionFields.name.is("work-one"))
          _ <- IO.println("matches=" + matches.map(_.name).sorted.mkString(","))
        } yield ()
      }
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
