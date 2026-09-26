package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Dimensions,
  PaneEdges,
  PaneId,
  PanePosition,
  SessionId,
  TmuxVersion,
  WindowId,
  WindowIndex
}
import io.github.libtmux.snapshot.{
  ClientState,
  PaneState,
  ServerSnapshot,
  SessionState,
  WindowContext,
  WindowState
}
import java.time.Instant
import java.util.{ArrayList, List => JList, Optional, OptionalLong}
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** `ServerSnapshot`/`PaneState`/... are Java's own records, used directly, so
  * this pins the `windowsOf`/`panesOf`/`session` grouping and
  * `Optional`/`OptionalLong` boundary behavior against the real Java types.
  */
final class SnapshotSuite extends FunSuite {
  private val session = new SessionId("$0")
  private val other = new SessionId("$1")
  private val window = new WindowId("@0")
  private val contexts = Vector(
    new WindowContext(session, new WindowIndex(0), window),
    new WindowContext(session, new WindowIndex(2), window),
    new WindowContext(other, new WindowIndex(1), window)
  )
  private val size = new Dimensions(80, 24)

  private def javaSnapshot: ServerSnapshot = {
    val windows = new ArrayList[WindowState]()
    val panes = new ArrayList[PaneState]()
    contexts.foreach { context =>
      windows.add(
        new WindowState(context, "editor", true, 1, true, size, "layout")
      )
      panes.add(
        new PaneState(
          context,
          new PaneId("%0"),
          0,
          true,
          "cat",
          size,
          new PanePosition(0, 0),
          "",
          "",
          OptionalLong.empty(),
          new PaneEdges(true, true, true, true),
          Optional.empty[java.lang.Boolean]()
        )
      )
    }
    ServerSnapshot.of(
      Instant.EPOCH,
      123L,
      new TmuxVersion(3, 2, "a"),
      JList.of(
        new SessionState(session, "one", false, 2),
        new SessionState(other, "two", false, 1)
      ),
      windows,
      panes,
      JList.of(new ClientState("client", Optional.empty[SessionId]()))
    )
  }

  test("capture preserves linked occurrences and optional metadata") {
    val snapshot = javaSnapshot
    assertEquals(snapshot.capturedAt(), Instant.EPOCH)
    assertEquals(snapshot.serverPid().toScala, Some(123L))
    assertEquals(snapshot.windows().asScala.map(_.context()).toVector, contexts)
    assertEquals(snapshot.panes().asScala.map(_.context()).toVector, contexts)
    assertEquals(
      snapshot.panes().asScala.map(_.id()).toVector.distinct,
      Vector(new PaneId("%0"))
    )
    assertEquals(
      snapshot.windowsOf(session).asScala.map(_.context()).toVector,
      contexts.take(2)
    )
    assertEquals(snapshot.panesOf(contexts(2)).size(), 1)
    assertEquals(
      snapshot.panes().asScala.map(_.pid().toScala).toVector,
      Vector(None, None, None)
    )
    assertEquals(
      snapshot
        .panes()
        .asScala
        .map(_.floating().toScala.map(_.booleanValue()))
        .toVector,
      Vector(None, None, None)
    )
    assertEquals(
      snapshot.panes().asScala.map(_.currentPath()).toVector,
      Vector("", "", "")
    )
    assertEquals(snapshot.clients().get(0).session().toScala, None)
    assertEquals(snapshot.session("missing").toScala, None)
  }

  test("conversion distinguishes absent from false and process zero") {
    val state = new PaneState(
      contexts.head,
      new PaneId("%0"),
      0,
      false,
      "",
      size,
      new PanePosition(0, 0),
      "",
      "/tmp",
      OptionalLong.of(0L),
      new PaneEdges(false, false, false, false),
      Optional.of(java.lang.Boolean.FALSE)
    )
    assertEquals(state.pid().toScala, Some(0L))
    assertEquals(state.floating().toScala.map(_.booleanValue()), Some(false))
    assertEquals(state.currentPath(), "/tmp")
  }
}
