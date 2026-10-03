package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{ServerConfig, ServerEndpoint, SessionSpec}
import java.nio.file.Path
import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** Create a session running cat. */
object NewSession {
  def main(args: Array[String]): Unit = {
    try {
      require(args.length == 3, "expected: tmux-binary socket-path config-file")
      val config = ServerConfig
        .builder()
        .binary(args(0))
        .endpoint(ServerEndpoint.socketPath(Path.of(args(1))))
        .configFile(Path.of(args(2)))
        .build()
      Using.resource(Server.open(config)) { server =>
        val session = server.newSession(
          SessionSpec.builder().named("api-session").running("/bin/cat").build()
        )
        println(s"created=${session.name}")
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
