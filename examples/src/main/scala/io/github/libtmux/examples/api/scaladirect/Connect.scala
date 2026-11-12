package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.scaladsl.*
import scala.util.Using
import scala.util.control.NonFatal

/** Connect to a private tmux server. */
object Connect {
  def main(args: Array[String]): Unit = {
    try {
      Using.resource(Server.start()) { server =>
        println(s"connected=${server.isAlive()}")
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
