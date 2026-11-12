package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{SessionSpec, SplitSpec, WindowSpec}
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.query.*
import scala.util.Using
import scala.util.control.NonFatal

/** Select sessions with a typed name filter. */
object Query {
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
        val matches = server.sessions(Session.name.is("work-one"))
        println("matches=" + matches.map(_.name).sorted.mkString(","))
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
