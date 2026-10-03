package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{ServerConfig, ServerEndpoint, WindowSpec}
import java.nio.file.Path
import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** Create a window and read the updated session. */
object NewWindow {
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
        val session =
          server.session("work-one").getOrElse(sys.error("missing work-one"))
        val window = session.newWindow(
          WindowSpec.builder().named("api-window").running("/bin/cat").build()
        )
        println(s"created=${window.name}")
        val refreshed =
          server.session("work-one").getOrElse(sys.error("missing work-one"))
        println("windows=" + refreshed.windows.map(_.name).sorted.mkString(","))
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
