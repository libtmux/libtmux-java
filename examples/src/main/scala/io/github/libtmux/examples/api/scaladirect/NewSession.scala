package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{SessionSpec}
import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** Create a session running cat. */
object NewSession {
  def main(args: Array[String]): Unit = {
    try {
      Using.resource(Server.start()) { server =>
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
