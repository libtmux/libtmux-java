package io.github.libtmux.scaladsl.examples

import io.github.libtmux.{ServerConfig, SessionSpec}
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.ox.Flows
import java.util.concurrent.{CountDownLatch, TimeUnit}
import _root_.ox.{fork, releaseAfterScope, supervised, useCloseableInScope}

/** Watches a session's live view as an Ox `Flow` inside a supervised scope: a
  * fork waits for the window count to grow while the main body adds a window.
  * The scope owns the server, the session and the view, releasing them in
  * reverse when it ends. The window is added only once the flow is known to be
  * listening, so the change cannot slip past it.
  */
object WatchWithOx {
  def main(arguments: Array[String]): Unit = println(
    run(ExampleRuntime.config(arguments))
  )

  def run(config: ServerConfig): String = supervised {
    val server = useCloseableInScope(Server.open(config))
    require(server.isAlive(), "example requires an existing tmux server")
    val session = server.newSession(
      SessionSpec
        .builder()
        .named(ExampleRuntime.name("scala-ox"))
        .running("cat")
        .build()
    )
    releaseAfterScope(session.kill())
    val live = useCloseableInScope(LiveView.attach(session))
    val before = live.current.snapshot.windows().size()
    val listening = new CountDownLatch(1)
    val grown = fork {
      Flows
        .liveView(live)
        .tap(_ => listening.countDown())
        .filter(_.snapshot.windows().size() > before)
        .take(1)
        .runToList()
    }
    require(listening.await(10, TimeUnit.SECONDS), "the flow never started")
    session.newWindow("added")
    val after = grown.join().head.snapshot.windows().size()
    s"windows went from $before to $after"
  }
}
