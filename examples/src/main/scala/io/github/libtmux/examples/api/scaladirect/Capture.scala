package io.github.libtmux.examples.api.scaladirect

import io.github.libtmux.{SessionSpec, TextOutcome}
import io.github.libtmux.scaladsl.*
import scala.concurrent.duration.*
import scala.util.Using
import scala.util.control.NonFatal

/** Send input and wait for output before capturing the pane. */
object Capture {
  def main(args: Array[String]): Unit = {
    try {
      Using.resource(Server.start()) { server =>
        val session = server.newSession(
          SessionSpec.builder().named("api-capture").running("/bin/sh").build()
        )
        val pane = session.windows.head.panes.head
        pane.sendKeys(Vector("printf 'api-%s\\n' keys", "Enter"))
        require(
          !pane.awaitText("api-keys", 5.seconds).equals(TextOutcome.TIMED_OUT),
          "timed out waiting for keys output"
        )
        pane.sendLine("printf 'api-%s\\n' line")
        require(
          !pane.awaitText("api-line", 5.seconds).equals(TextOutcome.TIMED_OUT),
          "timed out waiting for line output"
        )
        val lines = pane.capture()
        Vector("api-keys", "api-line").foreach { expected =>
          require(
            lines.exists(_.trim.endsWith(expected)),
            "capture is missing " + expected
          )
          println(expected)
        }
      }
    } catch {
      case NonFatal(error) =>
        System.err.println("Example failed: " + error.getMessage)
        sys.exit(1)
    }
  }
}
