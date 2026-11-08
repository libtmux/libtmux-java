package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{SessionSpec, SplitSpec, WindowSpec}
import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** List all windows, then the windows captured for one session. */
object ListWindows {
  def main(args: Array[String]): Unit = {
    try {
      Using.resource(Server.start()) { server =>
        val workOne = server.newSession(
          SessionSpec
            .builder()
            .named("work-one")
            .firstWindowNamed("editor")
            .running("/bin/cat")
            .build()
        )
        workOne.windows.head.split(
          SplitSpec.builder().toRight().running("/bin/cat").build()
        )
        workOne.newWindow(
          WindowSpec
            .builder()
            .named("logs")
            .running("/bin/cat")
            .detached()
            .build()
        )
        server.newSession(
          SessionSpec
            .builder()
            .named("work-two")
            .firstWindowNamed("editor")
            .running("/bin/cat")
            .build()
        )
        println("windows=" + server.windows().map(_.name).sorted.mkString(","))
        val session =
          server.session("work-one").getOrElse(sys.error("missing work-one"))
        println(
          "session-windows=" + session.windows.map(_.name).sorted.mkString(",")
        )
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
