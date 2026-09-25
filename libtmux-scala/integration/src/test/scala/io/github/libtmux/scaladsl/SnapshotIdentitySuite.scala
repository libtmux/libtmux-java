package io.github.libtmux.scaladsl

import io.github.libtmux.exception.TargetGoneException
import io.github.libtmux.{Server => JavaServer, SessionSpec, WindowSpec}
import io.github.libtmux.junit5.NamedServerFixture
import java.util.concurrent.TimeUnit
import io.github.libtmux.scaladsl.blocking.Server
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.transport.{
  CommandRequest,
  CommandResult,
  ProcessTransport,
  TmuxTransport
}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite

final class SnapshotIdentitySuite extends FunSuite {
  test("pure traversal preserves link occurrences and Java handle identity") {
    OwnedTmux.use { fixture =>
      val counted = fixture.own(new CountedTransport)
      val java = fixture.own(JavaServer.using(fixture.config, counted))
      val server = Server.fromJava(java)
      val first = java.sessions().get(0)
      val second = java.newSession(
        SessionSpec.builder().named("second").running("cat").build()
      )
      val physical = first.windows().get(0).id().value()
      assertEquals(
        java
          .cmd("link-window", "-s", physical, "-t", first.id().value() + ":4")
          .exitCode(),
        0
      )
      assertEquals(
        java
          .cmd("link-window", "-s", physical, "-t", second.id().value() + ":4")
          .exitCode(),
        0
      )
      val sessions = server.sessions()
      val calls = counted.calls.get()
      val windows = sessions.flatMap(_.windows)
      val panes = windows.flatMap(_.panes)
      assertEquals(windows.size, 4)
      assertEquals(windows.map(_.info.context).distinct.size, 4)
      assertEquals(panes.size, 4)
      assertEquals(panes.map(_.info.id).distinct.size, 2)
      val links = windows.filter(_.info.context.window().value() == physical)
      assertEquals(links.distinct.size, 3)
      assertEquals(links.flatMap(_.panes).distinct.size, 1)
      panes.foreach { pane =>
        assertEquals(pane.window.info.context, pane.info.context)
        assertEquals(pane.hashCode(), pane.asJava.hashCode())
        assertEquals(pane, blocking.Pane.fromJava(pane.asJava))
        assert(pane.toString.nonEmpty)
      }
      sessions.foreach(_.activePane.map(_.info))
      assertEquals(counted.calls.get(), calls)
      val captured = links.head
      val renamed = captured.rename("changed")
      assertEquals(renamed.info.name, "changed")
      assertEquals(captured.info.name, first.windows().get(0).name())
      assertEquals(captured, renamed)
      server.close()
      intercept[IllegalStateException](captured.refresh())
      intercept[IllegalStateException](server.panes())
      assert(java.isAlive())
    }
  }

  test("captured window placement rejects a replacement in the same slot") {
    OwnedTmux.use { fixture =>
      val server = fixture.own(Server.fromJava(fixture.server))
      val session = server.sessions().head
      val first = session.windows.head
      assertEquals(
        server
          .cmd(
            "link-window",
            "-s",
            first.info.context.window().value(),
            "-t",
            session.info.id.value() + ":5"
          )
          .exitCode,
        0
      )
      val stale = server.windows().find(_.info.context.index().value() == 5).get
      stale.unlink()
      val replacement = session.newWindow(
        WindowSpec
          .builder()
          .named("replacement")
          .atIndex(5)
          .detached()
          .running("cat")
          .build()
      )
      intercept[TargetGoneException](stale.select())
      intercept[TargetGoneException](stale.unlink())
      intercept[TargetGoneException](stale.moveTo(session, 6))
      intercept[TargetGoneException](stale.refresh())
      assertEquals(
        server.window(replacement.info.context).map(_.info.name),
        Some("replacement")
      )
      assertEquals(
        server.session(session.info.id).get.activeWindow.get.info.context,
        first.info.context
      )
    }
  }

  test(
    "server replacement and other endpoints cannot inherit captured handles"
  ) {
    OwnedTmux.use { fixture =>
      val server = fixture.own(Server.fromJava(fixture.server))
      val before = server.panes().head
      OwnedTmux.use { other =>
        val otherPane = blocking.Pane.fromJava(other.server.panes().get(0))
        assertEquals(before.info.id, otherPane.info.id)
        assertNotEquals(before, otherPane)
      }
      fixture.server.killServer()
      fixture.serverProcess.onExit().get(10, TimeUnit.SECONDS)
      fixture.server.newSession(
        SessionSpec.builder().named("replacement").running("cat").build()
      )
      fixture.own(
        NamedServerFixture.own(
          fixture.server,
          fixture.socket,
          fixture.directory
        )
      )
      val replacement = server.panes().head
      assertEquals(before.info.id, replacement.info.id)
      assertNotEquals(before, replacement)
      intercept[TargetGoneException](before.refresh())
      intercept[TargetGoneException](
        before.sendLiteral("must not reach replacement")
      )
      assertEquals(replacement.capture(), Vector.empty[String])
    }
  }

  private final class CountedTransport extends TmuxTransport {
    private val delegate = new ProcessTransport()
    val calls = new AtomicInteger()
    override def execute(request: CommandRequest): CommandResult = {
      calls.incrementAndGet()
      delegate.execute(request)
    }
    override def close(): Unit = delegate.close()
  }
}
