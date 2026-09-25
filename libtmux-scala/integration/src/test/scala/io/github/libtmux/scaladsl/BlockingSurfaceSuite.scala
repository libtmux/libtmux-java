package io.github.libtmux.scaladsl

import io.github.libtmux.{
  CaptureSpec,
  Layout,
  LibTmuxException,
  OptionKey,
  ServerNotRunningException,
  SessionSpec,
  SplitSpec,
  TextOutcome,
  TmuxFormats,
  TmuxVersion,
  UnsupportedTmuxVersionException,
  WakeReason
}
import io.github.libtmux.control.{
  ControlClient,
  ControlEndedException,
  Delivery,
  Notification
}
import io.github.libtmux.scaladsl.blocking.Server
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import munit.FunSuite
import scala.collection.immutable.VectorMap
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Using

final class BlockingSurfaceSuite extends FunSuite {
  test("filtered reads and timed calls reach the same Java overloads") {
    OwnedTmux.use { fixture =>
      val server = fixture.own(Server.fromJava(fixture.server))
      server.newSession(
        SessionSpec.builder().named("filtered").running("cat").build()
      )
      val deadline = Duration.ofSeconds(5)

      assertEquals(
        server
          .sessions(io.github.libtmux.Session_.name().is("filtered"))
          .map(_.info.name),
        Vector("filtered")
      )
      assertEquals(
        server.windows(io.github.libtmux.Window_.index().atLeast(0)).size,
        server.windows().size
      )
      assertEquals(
        server.panes(io.github.libtmux.Pane_.index().is(99)),
        Vector.empty
      )
      val pane = server
        .sessions(io.github.libtmux.Session_.name().is("filtered"))
        .head
        .windows
        .head
        .panes
        .head
      var checked = 0
      pane.sendLiteral(Vector("typed"), () => checked += 1)
      assertEquals(checked, 1)
      assertEquals(
        pane.awaitText(
          "never-shown",
          Duration.ofMillis(200),
          Duration.ofMillis(20)
        ),
        io.github.libtmux.TextOutcome.TIMED_OUT
      )
      assert(server.isAlive(deadline))
      server.killServer(deadline)
      assert(!server.isAlive(deadline))
    }
  }

  test("Java operations the facade wraps act on tmux in the facade's scope") {
    OwnedTmux.use { fixture =>
      val server = fixture.own(Server.fromJava(fixture.server))
      val session = server.newSession(
        SessionSpec.builder().named("wrapped").running("cat").build()
      )
      val pane = session.windows.head.panes.head
      val deadline = Duration.ofSeconds(5)

      server.requireAlive()
      assertEquals(server.attachedSessions(), Vector.empty)
      assertEquals(
        server.variables(Vector("session_name", "pid")).keys.toVector,
        Vector("session_name", "pid")
      )
      assertEquals(
        server.paneFields(Vector("pane_dead")),
        VectorMap.from(
          server.panes().map(_.info.id -> VectorMap("pane_dead" -> "0"))
        )
      )
      assert(!server.cmd("kill-window", "-t", "@999").succeeded)
      intercept[LibTmuxException](server.run("kill-window", "-t", "@999"))

      session.setHistoryLimit(1234)
      assertEquals(
        pane.split().variables(Vector("history_limit")),
        VectorMap("history_limit" -> "1234")
      )
      pane.respawn(Vector("sh", "-c", "echo respawned; exec cat"))
      assertNotEquals(
        pane.awaitText("respawned", deadline),
        TextOutcome.TIMED_OUT
      )
      assertEquals(
        pane.await(_.info.currentCommand == "cat", deadline),
        WakeReason.SIGNALLED
      )

      val bounded = server.within(deadline)
      assertEquals(bounded.sessions(), server.sessions())
      bounded.close()
      assert(server.sessions().contains(session))
      val derived = server.within(deadline)
      server.close()
      intercept[IllegalStateException](derived.sessions())
      assert(fixture.server.isAlive())
    }
  }

  test("control attaches to the captured session and reports its changes") {
    OwnedTmux.use { fixture =>
      val server = fixture.own(Server.fromJava(fixture.server))
      val session = server.newSession(
        SessionSpec.builder().named("watched").running("cat").build()
      )
      Using.resource(server.control(session, Duration.ofSeconds(5))) { client =>
        Using.resource(client.subscribeEvents(32)) { events =>
          session.windows.head.rename("seen")
          val renamed = Iterator
            .continually(events.next(Duration.ofSeconds(5)).toScala)
            .takeWhile(_.isDefined)
            .flatten
            .map(Delivery.kept(_).notification())
            .collectFirst { case r: Notification.WindowRenamed => r.name() }
          assertEquals(renamed, Some("seen"))
        }
      }
    }
  }

  test(
    "client refresh distinguishes changed attachment, detachment and dead server"
  ) {
    OwnedTmux.use { fixture =>
      val deadline = Duration.ofMillis(800)
      val server = fixture.own(Server.fromJava(fixture.server))
      val first = server.sessions().head
      val second = server.newSession(
        SessionSpec.builder().named("elsewhere").running("cat").build()
      )
      val observer = fixture.own(
        ControlClient.attachUnfenced(fixture.config, first.info.id, deadline)
      )
      val events = fixture.own(observer.subscribeEvents(32))
      val target = fixture.own(
        ControlClient.attachUnfenced(fixture.config, first.info.id, deadline)
      )
      val ready = target.send(
        Vector("display-message", "-p", "#{client_name}").asJava,
        deadline
      )
      assert(ready.succeeded())
      assertEquals(ready.lines().size(), 1)
      val name = ready.lines().get(0)
      val client = server.clients().find(_.info.name == name).get
      val captured = client.attachment.get
      assertEquals(captured.session.info.id, first.info.id)
      assertEquals(captured.activeWindow.info.context.session(), first.info.id)
      assert(captured.activePane.info.active)

      def awaitEvent(matches: Notification => Boolean): Unit = {
        val until = System.nanoTime() + deadline.toNanos
        var matched = false
        while (!matched) {
          val remaining = until - System.nanoTime()
          assert(remaining > 0, "client notification deadline expired")
          val step = events.next(Duration.ofNanos(remaining)).toScala
          assert(step.isDefined, "expected a client notification")
          matched = step.get match {
            case item: Delivery.Event[_] =>
              matches(
                item
                  .value()
                  .asInstanceOf[io.github.libtmux.control.ControlEvent]
                  .notification()
              )
            case _: Delivery.Gap[_] => false
          }
        }
        assertEquals(events.droppedCount(), 0L)
      }

      client.redraw()
      client.switchTo(second)
      awaitEvent {
        case changed: Notification.ClientSessionChanged =>
          changed.client() == name && changed.session() == second.info.id
        case _ => false
      }
      assertEquals(
        client.attachment.map(_.session.info.id),
        Some(first.info.id)
      )
      assertEquals(
        client.refresh().flatMap(_.attachment).map(_.session.info.id),
        Some(second.info.id)
      )
      assertEquals(
        client.fetchAttachment().map(_.activeWindow.info.context.session()),
        Some(second.info.id)
      )
      client.detach()
      awaitEvent {
        case detached: Notification.ClientDetached => detached.client() == name
        case _                                     => false
      }
      target.close()
      assertEquals(client.refresh(), None)
      assertEquals(client.fetchAttachment(), None)
      assert(server.isAlive())
      server.killServer()
      fixture.serverProcess.onExit().get(800, TimeUnit.MILLISECONDS)
      intercept[ServerNotRunningException](client.refresh())
      intercept[ServerNotRunningException](client.fetchAttachment())
    }
  }

  test(
    "blocking operations preserve ownership, targets, text and optional results"
  ) {
    OwnedTmux.use { fixture =>
      val server = Server.open(fixture.config)
      try {
        val session = server.newSession(
          SessionSpec.builder().named("scala").running("cat").build()
        )
        val window = session.newWindow(
          io.github.libtmux.WindowSpec
            .builder()
            .named("work")
            .running("cat")
            .build()
        )
        val pane = window.split(SplitSpec.builder().running("cat").build())
        window.selectLayout(Layout.EVEN_HORIZONTAL)
        pane.select()
        assertEquals(pane.refresh().info.active, true)
        assertEquals(
          server.session(session.info.id).map(_.info.name),
          Some("scala")
        )
        assertEquals(
          server.pane(pane.info.id).map(_.info.id),
          Some(pane.info.id)
        )
        assertEquals(
          server.window(window.info.context).map(_.info.context),
          Some(window.info.context)
        )
        assertEquals(server.snapshot().panesOf(window.info.context).size, 2)
        assertEquals(pane.expand("#{pane_id}"), pane.info.id.value())
        assertEquals(
          pane.expand(TmuxFormats.literal("#(echo unsafe)")),
          "#(echo unsafe)"
        )
        assertEquals(
          pane.capture(CaptureSpec.builder().build()),
          Vector.empty[String]
        )
        assertEquals(pane.mode(), None)
        pane.copyMode()
        assert(pane.mode().isDefined)
        pane.exitMode()
        assertEquals(pane.mode(), None)
        assert(server.cmd(Vector("no-such-scala-command")).exitCode != 0)
        assertEquals(server.session("does-not-exist"), None)
      } finally server.close()
      assert(fixture.server.isAlive())
      intercept[IllegalStateException](server.sessions())
    }
  }

  test(
    "options hooks environment and buffers retain their Java scope contracts"
  ) {
    OwnedTmux.use { fixture =>
      val server = Server.fromJava(fixture.server)
      val session = server.sessions().head
      if (
        !sys.props
          .get("libtmux.scala.settings.mutant")
          .contains("omit-inheritance")
      ) {
        server.globalOptions.set("@scala-option", "parent")
        server.globalOptions.set("history-limit", "1234")
      }
      assertEquals(session.options.get("@scala-option"), Some("parent"))
      assert(!session.options.all().contains("@scala-option"))
      assert(!session.options.effective().contains("@scala-option"))
      assertEquals(session.options.get("history-limit"), Some("1234"))
      assert(!session.options.all().contains("history-limit"))
      assertEquals(session.options.effective()("history-limit"), "1234")
      session.options.set("@scala-option", "")
      assertEquals(session.options.get("@scala-option"), Some(""))
      assertEquals(session.options.effective()("@scala-option"), "")
      session.options.unset("@scala-option")
      session.options.set(OptionKey.HISTORY_LIMIT, Integer.valueOf(50000))
      assertEquals(
        session.options.get(OptionKey.HISTORY_LIMIT).map(_.intValue()),
        Some(50000)
      )
      session.options.set(OptionKey.MOUSE, java.lang.Boolean.TRUE)
      assertEquals(
        session.options.get(OptionKey.MOUSE).map(_.booleanValue()),
        Some(true)
      )
      session.options.set("update-environment[40]", "LIBTMUX_SCALA_INDEXED")
      assertEquals(
        session.options.get("update-environment[40]"),
        Some("LIBTMUX_SCALA_INDEXED")
      )
      assertEquals(
        session.options.all()("update-environment[40]"),
        "LIBTMUX_SCALA_INDEXED"
      )
      val variable = "LIBTMUX_SCALA_TEST_VALUE"
      server.environment.set(variable, "parent")
      assertEquals(session.environment.get(variable), None)
      assertEquals(session.environment.effective()(variable), "parent")
      session.environment.set(variable, "")
      assertEquals(session.environment.get(variable), Some(""))
      session.environment.remove(variable)
      assert(session.environment.isRemoved(variable))
      assert(session.environment.removed().contains(variable))
      assert(!session.environment.effective().contains(variable))
      session.environment.unset(variable)
      assertEquals(session.environment.effective()(variable), "parent")
      session.hooks.set(
        "after-new-window",
        Vector("set-environment", "-g", "LIBTMUX_SCALA_HOOK", "first")
      )
      session.hooks.append(
        "after-new-window",
        "set-environment -g LIBTMUX_SCALA_HOOK second"
      )
      assertEquals(session.hooks.all()("after-new-window").size, 2)
      session.hooks.run("after-new-window")
      assertEquals(server.environment.get("LIBTMUX_SCALA_HOOK"), Some("second"))
      session.hooks.unset("after-new-window")
      val file = fixture.directory.resolve("buffer.txt")
      try {
        server.buffers.set("scala-buffer", "first\nsecond")
        assertEquals(server.buffers.show("scala-buffer"), "first\nsecond")
        server.buffers.save("scala-buffer", file)
        server.buffers.load("loaded", file)
        assertEquals(server.buffers.show("loaded"), "first\nsecond")
        assertEquals(
          server.buffers.list().map(_.name()).toSet,
          Set("scala-buffer", "loaded")
        )
        if (fixture.server.version().atLeast(new TmuxVersion(3, 4, ""))) {
          server.buffers.delete("loaded")
          server.buffers.delete("scala-buffer")
          assertEquals(server.buffers.list(), Vector.empty)
        } else {
          intercept[UnsupportedTmuxVersionException](
            server.buffers.delete("loaded")
          )
          assertEquals(
            server.buffers.list().map(_.name()).toSet,
            Set("scala-buffer", "loaded")
          )
        }
      } finally Files.deleteIfExists(file)
      val options = session.options
      val environment = session.environment
      val hooks = session.hooks
      val buffers = server.buffers
      server.close()
      intercept[IllegalStateException](options.get("@scala-option"))
      intercept[IllegalStateException](environment.get(variable))
      intercept[IllegalStateException](hooks.all())
      intercept[IllegalStateException](buffers.list())
      assert(fixture.server.isAlive())
    }
  }
}
