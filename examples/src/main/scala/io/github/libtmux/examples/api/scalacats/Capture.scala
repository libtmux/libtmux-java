package io.github.libtmux.examples.api.scalacats

import io.github.libtmux.{
  ServerConfig,
  ServerEndpoint,
  SessionSpec,
  TextOutcome
}
import java.nio.file.Path
import io.github.libtmux.scaladsl.cats.*
import scala.concurrent.duration.*
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*

/** Send input and wait for output before capturing the pane. */
object Capture extends IOApp {
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
          session <- server.newSession(
            SessionSpec
              .builder()
              .named("api-capture")
              .running("/bin/sh")
              .build()
          )
          pane = session.windows.head.panes.head
          _ <- pane.sendKeys(Vector("printf 'api-%s\\n' keys", "Enter"))
          keys <- pane.awaitText("api-keys", 30.seconds)
          _ <- IO.raiseWhen(keys.equals(TextOutcome.TIMED_OUT))(
            new IllegalStateException("timed out waiting for keys output")
          )
          _ <- pane.sendLine("printf 'api-%s\\n' line")
          line <- pane.awaitText("api-line", 30.seconds)
          _ <- IO.raiseWhen(line.equals(TextOutcome.TIMED_OUT))(
            new IllegalStateException("timed out waiting for line output")
          )
          lines <- pane.capture()
          _ <- Vector("api-keys", "api-line").traverse_ { expected =>
            IO.raiseUnless(lines.exists(_.trim.equals(expected)))(
              new IllegalStateException("capture is missing " + expected)
            ) *> IO.println(expected)
          }
        } yield ()
      }
    }
    program.as(ExitCode.Success).handleErrorWith { error =>
      IO(System.err.println("Example failed: " + error.getMessage))
        .as(ExitCode.Error)
    }
  }
}
