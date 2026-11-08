package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{SessionSpec, SplitSpec, WindowSpec}
import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** List all panes, then the panes captured for one window. */
object ListPanes {
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
        println(s"panes=${server.panes().size}")
        val session =
          server.session("work-one").getOrElse(sys.error("missing work-one"))
        val window = session.windows
          .find(_.name.equals("editor"))
          .getOrElse(sys.error("missing editor"))
        println(s"window-panes=${window.panes.size}")
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
