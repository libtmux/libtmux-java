package io.github.libtmux.scaladsl.examples

import io.github.libtmux.{ServerConfig, SessionSpec}
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.ox.Flows
import java.util.concurrent.{CountDownLatch, TimeUnit}
import _root_.ox.{fork, supervised}
import scala.util.Using

/** Watches a session's live view as an Ox `Flow` inside a supervised scope: a
  * fork waits for the window count to grow while the main body adds a window.
  * The window is added only once the flow is known to be listening, so the
  * change cannot slip past it.
  */
object WatchWithOx {
  def main(arguments: Array[String]): Unit = println(
    run(ExampleRuntime.config(arguments))
  )

  def run(config: ServerConfig): String = Using.resource(Server.open(config)) {
    server =>
      require(server.isAlive(), "example requires an existing tmux server")
      val session = server.newSession(
        SessionSpec
          .builder()
          .named(ExampleRuntime.name("scala-ox"))
          .running("cat")
          .build()
      )
      try
        Using.resource(LiveView.attach(session)) { live =>
          val before = live.current.snapshot.windows().size()
          val listening = new CountDownLatch(1)
          supervised {
            val grown = fork {
              Flows
                .liveView(live)
                .tap(_ => listening.countDown())
                .filter(_.snapshot.windows().size() > before)
                .take(1)
                .runToList()
            }
            require(
              listening.await(10, TimeUnit.SECONDS),
              "the flow never started"
            )
            session.newWindow("added")
            val after = grown.join().head.snapshot.windows().size()
            s"windows went from $before to $after"
          }
        }
      finally session.kill()
  }
}
