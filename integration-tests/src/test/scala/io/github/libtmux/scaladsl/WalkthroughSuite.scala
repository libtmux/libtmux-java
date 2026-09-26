package io.github.libtmux.scaladsl

import io.github.libtmux.exception.TargetGoneException
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.query._
import munit.FunSuite
import scala.concurrent.duration._
import scala.util.Using

/** The direct-style flagship scenario: open a server, create a session with a
  * window, split it, send a line and capture, filter with the query DSL and
  * take exactly one, then tear down. Every operation below is a real, generated
  * (or handwritten `WAIT`/`LIFECYCLE`) call against real tmux — nothing here is
  * a stand-in.
  */
final class WalkthroughSuite extends FunSuite {

  test("open, populate, query, and observe cardinality against a real server") {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession("build")
        // Session.windows()/Window.panes() are CAPTURED: they answer from the handle's own frozen
        // capture, not a live tmux query, so this collects handles directly rather than re-reading
        // session/logs after mutating past what they were captured with.
        val editorPane = session.windows.head.panes.head
        val logs = session.newWindow("logs")
        val logsPane = logs.panes.head
        val top = logs.split()
        val bottom = logs.split()

        bottom.sendLine("echo hello-from-bottom")
        assert(
          !bottom
            .awaitText("hello-from-bottom", 5.seconds)
            .equals(io.github.libtmux.TextOutcome.TIMED_OUT)
        )
        val captured = bottom.capture()
        assert(captured.exists(_.contains("hello-from-bottom")))

        // The query DSL: fields on the companion, symbolic and named operators, .matching as a
        // local filter, exactlyOne for strict cardinality.
        val myPanes = Vector(editorPane, logsPane, top, bottom)
        val onlyBottom = myPanes.matching(Pane.id.is(bottom.info.id.value()))
        assertEquals(onlyBottom.exactlyOne, Right(bottom))
        val everyPane =
          myPanes.matching(Pane.active.is(false) || Pane.active.is(true))
        assertEquals(
          everyPane.exactlyOne,
          Left(CardinalityError.MultipleMatches(2))
        )
        assertEquals(
          myPanes.matching(Pane.id.is("%not-a-real-pane")).exactlyOne,
          Left(CardinalityError.NoMatch)
        )

        assertEquals(session.info.name, "build")
        assertEquals(logs.info.name, "logs")
        assertEquals(top.window, logs)
        assert(!editorPane.info.context.equals(logs.info.context))

        session.kill()
        // panes/info are CAPTURED: they answer from the frozen capture, with no tmux I/O, so they
        // survive the session's death. select reaches tmux and finds the window gone.
        intercept[TargetGoneException](logs.select())
      }
      assert(fixture.server.isAlive())
    }
  }
}
