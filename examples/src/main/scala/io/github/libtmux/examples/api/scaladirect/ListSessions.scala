package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{ServerConfig, ServerEndpoint}
import java.nio.file.Path
import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** List the sessions returned by a server read. */
object ListSessions {
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
        println(
          "sessions=" + server.sessions().map(_.name).sorted.mkString(",")
        )
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
