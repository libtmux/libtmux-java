package io.github.libtmux.scaladsl.examples

import io.github.libtmux.{
  Layout,
  Pane_,
  ServerConfig,
  SessionSpec,
  SplitSpec,
  WakeReason
}
import io.github.libtmux.scaladsl.Queries
import io.github.libtmux.scaladsl.blocking.Server
import scala.util.Using

/** Builds and reads a linked workspace, then removes the sessions it created.
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
        window.split(SplitSpec.builder().running("cat").build())
        window.selectLayout(Layout.EVEN_HORIZONTAL)
        shell.select()

        shell.sendLiteral("printf 'literal-%s\\n' 'Enter #(...)'")
        shell.sendKeys(Vector("Enter"))
        val completed = name + "-input"
        shell.sendLine(
          "printf 'line-%s\\n' submitted; " +
            ExampleRuntime.shell(config, "wait-for", "-S", completed)
        )
        assert(
          server
            .channel(completed)
            .await(ExampleRuntime.deadline) == WakeReason.SIGNALLED
        )
        val screen = shell.capture()
        assert(screen.exists(_.contains("literal-Enter #(...)")))
        assert(screen.exists(_.contains("line-submitted")))

        val captured =
          server.panes().filter(_.info.context.session() == session.info.id)
        assert(captured.size == 2)
        assert(
          captured.filter(_.info.active) == captured.filter(
            Queries.panes(Pane_.active().isTrue())
          )
        )
        assert(
          Queries
            .oneOrNone(
              captured.filter(
                _.info.currentCommand == "__missing_example_command__"
              )
            )
            .isEmpty
        )
        assert(server.session(name + "-missing").isEmpty)

        val other = server.newSession(
          SessionSpec.builder().named(name + "-linked").running("cat").build()
        )
        try {
          window.linkTo(other)
          assert(
            server
              .cmd(
                "link-window",
                "-s",
                window.info.context.window().value(),
                "-t",
                session.info.id
                  .value() + ":" + (window.info.context.index().value() + 1)
              )
              .succeeded
          )
          val occurrences = server.panes().filter(_.info.id == shell.info.id)
          assert(occurrences.size == 3)
          assert(occurrences.map(_.info.context).distinct.size == 3)
          assert(occurrences.distinct.size == 1)
          assert(occurrences.map(_.refresh().info.context).distinct.size == 1)
        } finally other.kill()
      } finally session.kill()
      assert(server.isAlive())
  }
}
