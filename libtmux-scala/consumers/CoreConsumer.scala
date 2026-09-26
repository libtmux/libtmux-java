package io.github.libtmux.scaladsl.consumer

import io.github.libtmux.{SessionSpec, SplitSpec}
import io.github.libtmux.scaladsl.*
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.query.*
import scala.util.Using

/** Installed-consumer smoke test for the direct-style facade: open an owned
  * client, create a session, split a window, filter panes with the query DSL
  * and require exactly one match, look the session up by name, borrow the
  * same client without closing it, and confirm the owning fixture's daemon
  * outlives the whole scope.
  */
object CoreConsumer {
  def main(arguments: Array[String]): Unit = {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession(
          SessionSpec.builder().named("consumer-core").running("cat").build()
        )
        val window = session.windows.head
        val second = window.split(SplitSpec.builder().running("cat").build())
        val panes: Vector[Pane] = window.refresh().panes
        assert(panes.size == 2, "split must leave two panes")

        val selected = panes.matching(Pane.id.is(second.info.id.value()))
        selected.exactlyOne match {
          case Right(pane) => assert(pane == second)
          case Left(error) =>
            throw new AssertionError("expected exactly one match: " + error)
        }

        val found = server.session("consumer-core")
        assert(found.exists(_ == session))
        assert(server.snapshot().panes.size >= 2)

        // The facade is opaque over the Java client: fromJava is that same
        // client, not a wrapper, so closing it would close this one too.
        val borrowed = Server.fromJava(server.asJava)
        assert(borrowed.asJava eq server.asJava)
        assert(server.isAlive())

        session.kill()
      }
      assert(fixture.server.isAlive())
    }
    println(
      "CONSUMER_PASS core cleanup=true java=" + Runtime.version().feature()
    )
  }
}
