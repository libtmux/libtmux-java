package io.github.libtmux.scaladsl

import io.github.libtmux.{PaneMode, Server, SessionSpec, WakeReason}
import io.github.libtmux.batch.OperationOutcome
import io.github.libtmux.control.ControlClient
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import java.nio.file.Files
import java.time.Duration
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

final class JavaBoundarySuite extends FunSuite {
  private val deadline = Duration.ofSeconds(1)
  private def breakProducer: Boolean =
    sys.props.get("libtmux.scala.boundary.mutant").contains("producer")

  private def run(server: Server, words: String*): Unit = {
    server.run(words.toList.asJava)
    ()
  }

  private def control(fixture: OwnedTmux): ControlClient =
    fixture.own(
      ControlClient.attach(
        fixture.config,
        fixture.server.sessions().get(0).id(),
        deadline
      )
    )

  test("pane refresh follows physical identity across linked occurrences") {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val first = server.sessions().get(0)
      val window = first.windows().get(0)
      val pane = window.panes().get(0)
      val second = server.newSession(
        SessionSpec.builder().named("second").running("cat").build()
      )
      window.linkTo(second)
      if (!breakProducer)
        run(
          server,
          "link-window",
          "-s",
          window.id().value(),
          "-t",
          first.id().value() + ":7"
        )

      val occurrences = server
        .panes()
        .asScala
        .toVector
        .filter(_.id() == pane.id())
      val contexts = occurrences.map(_.window().context())
      assertEquals(contexts.distinct.size, 3)
      assertEquals(contexts.count(_.session() == first.id()), 2)
      assertEquals(occurrences.distinct.size, 1)

      val refreshed = occurrences.map(_.refresh())
      assertEquals(
        refreshed.map(_.window().context()).distinct,
        Vector(contexts.head)
      )
      assertEquals(occurrences.map(_.window().context()), contexts)
    }
  }

  test("hook listing retains command order and discards sparse indices") {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val session = server.sessions().get(0)
      val event = "after-new-window"
      val later = if (breakProducer) 1 else 40
      run(
        server,
        "set-hook",
        "-t",
        session.id().value(),
        event + "[0]",
        "display-message -p first"
      )
      run(
        server,
        "set-hook",
        "-t",
        session.id().value(),
        event + "[" + later + "]",
        "display-message -p second"
      )

      val raw = server
        .cmd("show-hooks", "-t", session.id().value())
        .stdout()
        .asScala
        .toVector
        .filter(_.startsWith(event + "["))
      assertEquals(
        raw.map(_.takeWhile(_ != ' ')),
        Vector(event + "[0]", event + "[40]")
      )
      val dense = session.hooks().all().get(event).asScala.toVector
      assertEquals(dense, raw.map(_.dropWhile(_ != ' ').drop(1)))
      assertEquals(dense.size, 2)
    }
  }

  test(
    "buffer text preserves carriage returns and transport captures retain shapes"
  ) {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val contents = "first\r\n\r\nlast\n\n"
      server.buffers().set("text", contents)
      assertEquals(server.buffers().show("text"), "first\r\n\r\nlast")
      assertEquals(server.buffers().list().get(0).size(), contents.length)

      val pane = server.panes().get(0)
      val processRows = pane.capture().asScala.toVector
      val reply = control(fixture).send(
        List("capture-pane", "-p", "-t", pane.id().value()).asJava,
        deadline
      )
      assert(reply.succeeded())
      val originalRows = reply.lines().asScala.toVector
      val controlRows =
        if (breakProducer) originalRows.filter(_.nonEmpty) else originalRows
      assertEquals(processRows, Vector.empty[String])
      assert(controlRows.nonEmpty, "control retains the blank screen rows")
      assert(controlRows.forall(_.isEmpty))
      assertEquals(
        controlRows.reverse.dropWhile(_.isEmpty).reverse,
        processRows
      )
    }
  }

  test("basic copy mode composes with explicit raw copy commands") {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val pane = server.panes().get(0)
      assertEquals(pane.mode().toScala, None)
      if (!breakProducer) pane.copyMode()
      assertEquals(pane.mode().toScala, Some(PaneMode.COPY))
      run(server, "send-keys", "-t", pane.id().value(), "-X", "cursor-up")
      assertEquals(pane.mode().toScala, Some(PaneMode.COPY))
      pane.exitMode()
      assertEquals(pane.mode().toScala, None)
      run(server, "clock-mode", "-t", pane.id().value())
      assertEquals(pane.mode().toScala, Some(PaneMode.CLOCK))
      pane.exitMode()
      assertEquals(pane.mode().toScala, None)
    }
  }

  test("control acknowledgement precedes channel-gated command completion") {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val attached = control(fixture)
      val marker = fixture.directory.resolve("completed")
      val prefix = fixture.config
        .endpointCommand()
        .asScala
        .map(word => "'" + word.replace("'", "'\"'\"'") + "'")
        .mkString(" ")
      val release = server.channel("scala-boundary-release")
      val completed = server.channel("scala-boundary-completed")
      val script = prefix + " wait-for scala-boundary-release; : > '" +
        marker.toString + "'; " + prefix + " wait-for -S scala-boundary-completed"
      if (breakProducer) Files.writeString(marker, "premature completion")
      try {
        val reply = attached.send(List("run-shell", script).asJava, deadline)
        val absentAtAcknowledgement = !Files.exists(marker)
        release.signal()
        assertEquals(completed.await(deadline), WakeReason.SIGNALLED)
        assert(reply.succeeded())
        assert(
          absentAtAcknowledgement,
          "completion marker is absent before the release signal"
        )
        assert(Files.exists(marker), "completion marker follows the release")
      } finally {
        release.signal()
        Files.deleteIfExists(marker)
      }
    }
  }

  test("subscription reads do not distinguish owner close from server loss") {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val attached = control(fixture)
      val deliberate = fixture.own(attached.subscribeOutput(4))
      if (!breakProducer) deliberate.close()
      assert(deliberate.isClosed())
      assertEquals(deliberate.next(Duration.ZERO).toScala, None)

      val unexpected = fixture.own(attached.subscribeOutput(4))
      run(server, "kill-server")
      assertEquals(unexpected.next(deadline).toScala, None)
      assert(unexpected.isClosed())
      assert(!attached.isAlive())
    }
  }

  test("runtime batch failures differ from whole-group parse rejection") {
    OwnedTmux.use { fixture =>
      val server = fixture.server
      val runtime = server
        .batch()
        .add("display-message", "-p", "before")
        .add("select-window", "-t", "__scala_boundary_missing__")
        .add("display-message", "-p", "after")
        .run()
      assertEquals(
        runtime.operations().asScala.map(_.outcome()).toVector,
        Vector(
          OperationOutcome.COMPLETE,
          OperationOutcome.FAILED,
          OperationOutcome.SKIPPED
        )
      )
      assertEquals(
        runtime.operations().get(0).stdout().asScala.toVector,
        Vector("before")
      )

      val middle =
        if (breakProducer) List("display-message", "-p", "middle")
        else List("no-such-scala-boundary-command")
      val rejected = server
        .batch()
        .add("display-message", "-p", "before")
        .add(middle.asJava)
        .add("display-message", "-p", "after")
        .run()
      assert(!rejected.succeeded())
      assertEquals(
        rejected.operations().asScala.map(_.outcome()).toVector,
        Vector(
          OperationOutcome.FAILED,
          OperationOutcome.SKIPPED,
          OperationOutcome.SKIPPED
        )
      )
      assert(rejected.operations().asScala.forall(_.stdout().isEmpty()))
      assert(
        rejected
          .operations()
          .get(0)
          .stderr()
          .asScala
          .exists(_.contains("no-such-scala-boundary-command"))
      )
    }
  }
}
