package io.github.libtmux.scaladsl.examples

import io.github.libtmux.{Layout, ServerConfig, SessionSpec, SplitSpec}
// Wildcards, not named imports: examples lives beside io.github.libtmux.scaladsl, not inside it, so
// its generated and handwritten extension methods (isAlive, newSession, session, ...) need an
// explicit import rather than the enclosing-package visibility a nested package would get for free.
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.query.*
import scala.concurrent.duration._
import scala.util.Using

/** Builds a small workspace with the direct-style facade: a session with a
  * split window, queried with the typed field DSL, then torn down. Every handle
  * here is an opaque alias of the Java one — `server.newSession(...)` returns
  * the same object `io.github.libtmux.Server.newSession` would, with a
  * generated Scala-shaped signature.
  */
object BlockingWorkspace {
  def main(arguments: Array[String]): Unit = run(
    ExampleRuntime.config(arguments)
  )

  def run(config: ServerConfig): Unit = Using.resource(Server.open(config)) {
    server =>
      require(server.isAlive(), "example requires an existing tmux server")
      val name = ExampleRuntime.name("scala-blocking")
      val session = server.newSession(
        SessionSpec.builder().named(name).running("/bin/sh").build()
      )
      try {
        val window = session.windows.head
        val shell = window.panes.head
        val second = window.split(SplitSpec.builder().running("cat").build())
        window.selectLayout(Layout.EVEN_HORIZONTAL)
        shell.select()

        shell.sendLine("printf 'line-%s\\n' submitted")
        assert(
          !shell
            .awaitText("line-submitted", 5.seconds)
            .equals(io.github.libtmux.TextOutcome.TIMED_OUT)
        )
        val screen = shell.capture()
        assert(screen.exists(_.contains("line-submitted")))

        // The typed field DSL: Pane.command/.active on the handle's own companion, .matching as a
        // local filter, exactlyOne for strict cardinality.
        val panes = Vector(shell, second)
        assert(
          panes
            .matching(Pane.active.is(true) || Pane.active.is(false))
            .size == 2
        )
        val onlyShell = panes.matching(Pane.id.is(shell.info.id.value()))
        assert(onlyShell.exactlyOne.equals(Right(shell)), onlyShell)
        assert(server.session(name + "-missing").isEmpty)
      } finally session.kill()
      assert(server.isAlive())
  }
}
