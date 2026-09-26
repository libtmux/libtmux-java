package io.github.libtmux.scaladsl

import io.github.libtmux.{
  Client_,
  Pane => JavaPane,
  Pane_,
  Server => JavaServer,
  SessionSpec,
  Session_,
  SplitSpec,
  Window_
}
import io.github.libtmux.control.ControlClient
import io.github.libtmux.jackson.{FilterJson, LibTmuxModels, SchemaException}
import io.github.libtmux.query.{Fields, FilterExpr}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.query.Expr
import io.github.libtmux.transport.{
  CommandRequest,
  CommandResult,
  ProcessTransport,
  TmuxTransport
}
import java.util.{List => JList, Optional}
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import munit.FunSuite

/** A raw Java `FilterExpr`, built through the Java metamodel (`Pane_`,
  * `Window_`, ...) exactly as a caller outside this facade would, still filters
  * this facade's captured `Vector[Pane]`/... correctly once wrapped in the
  * one-line `Expr(...)` this facade's own `.matching` extension needs.
  */
final class QueryInteropSuite extends FunSuite {
  test(
    "native and Java expressions preserve captured order, context and realm without I/O"
  ) {
    OwnedTmux.use { fixture =>
      val process = fixture.own(new ProcessTransport())
      val calls = new AtomicInteger()
      val counted = new TmuxTransport {
        override def execute(request: CommandRequest): CommandResult = {
          calls.incrementAndGet()
          process.execute(request)
        }
        override def realm(): String = "scala-query-fixture"
        override def close(): Unit = ()
      }
      val javaServer = fixture.own(JavaServer.using(fixture.config, counted))
      val server = Server.fromJava(javaServer)
      val first = javaServer.sessions().get(0)
      val firstPane = first.windows().get(0).panes().get(0)
      firstPane.retitle("ScalaAlpha")
      firstPane
        .split(SplitSpec.builder().running("cat").build())
        .retitle("alpha")
      val second = javaServer.newSession(
        SessionSpec.builder().named("second").running("cat").build()
      )
      second.windows().get(0).panes().get(0).retitle("other")
      val windowId = first.windows().get(0).id().value()
      Vector(first, second).foreach { session =>
        assert(
          javaServer
            .cmd(
              "link-window",
              "-s",
              windowId,
              "-t",
              session.id().value() + ":4"
            )
            .succeeded()
        )
      }
      fixture.own(ControlClient.attachUnfenced(fixture.config, first.id()))
      val sessions = server.sessions()
      val windows = sessions.flatMap(_.windows)
      val panes = windows.flatMap(_.panes)
      val clients = server.clients()
      val localRealmPane = Pane.wrap(
        fixture.server.pane(panes.head.info.id).orElseThrow()
      )
      val before = calls.get()
      assertEquals(panes.size, 7)
      assertEquals(panes.map(_.info.id).distinct.size, 3)
      assertEquals(windows.map(_.info.context).distinct.size, 4)
      assertNotEquals(panes.head, localRealmPane)

      val cases: Vector[(FilterExpr[JavaPane], Pane => Boolean)] =
        Vector(
          Pane_.title().is("ScalaAlpha") -> ((pane: Pane) =>
            pane.info.title == "ScalaAlpha"
          ),
          Pane_.title().is("scalaalpha") -> ((pane: Pane) =>
            pane.info.title == "scalaalpha"
          ),
          Pane_.title().matches(Pattern.compile("Alpha")) -> ((pane: Pane) =>
            pane.info.title.contains("Alpha")
          ),
          Pane_.index().is(0) -> ((pane: Pane) => pane.info.index == 0),
          Pane_.index().atLeast(1) -> ((pane: Pane) => pane.info.index >= 1),
          Pane_.active().isTrue().and(Pane_.index().is(1)) -> ((pane: Pane) =>
            pane.info.active && pane.info.index == 1
          ),
          Pane_.title().is("other").or(Pane_.index().is(1)) -> ((pane: Pane) =>
            pane.info.title == "other" || pane.info.index == 1
          ),
          Pane_.title().is("other").negate() -> ((pane: Pane) =>
            pane.info.title != "other"
          )
        )
      cases.foreach { case (expression, native) =>
        assertEquals(
          panes
            .matching(Expr(expression))
            .map(pane => pane -> pane.info.context),
          panes.filter(native).map(pane => pane -> pane.info.context),
          expression.describe()
        )
      }
      assertEquals(
        Vector(panes.head, localRealmPane)
          .matching(Expr(Pane_.id().is(panes.head.info.id.value()))),
        Vector(panes.head, localRealmPane)
      )
      assertEquals(
        windows.matching(
          Expr(Window_.panes().any(Pane_.title().is("ScalaAlpha")))
        ),
        windows.filter(_.panes.exists(_.info.title == "ScalaAlpha"))
      )
      assertEquals(
        windows.matching(
          Expr(Window_.session().is(Session_.name().is("second")))
        ),
        windows.filter(_.session.info.name == "second")
      )
      assertEquals(
        sessions.matching(
          Expr(Session_.windows().any(Window_.index().is(4)))
        ),
        sessions.filter(_.windows.exists(_.info.context.index().value() == 4))
      )
      assert(clients.nonEmpty)
      assertEquals(
        clients.matching(
          Expr(Client_.session().is(Session_.name().is("fixture")))
        ),
        clients.filter(_.session.exists(_.info.name == "fixture"))
      )

      val always = FilterExpr.and[JavaPane](JList.of[FilterExpr[JavaPane]]())
      val never = FilterExpr.or[JavaPane](JList.of[FilterExpr[JavaPane]]())
      val explodes = Fields
        .text[JavaPane](
          "unreachable",
          _ => throw new AssertionError("predicate did not short-circuit")
        )
        .is("x")
      assertEquals(panes.matching(Expr(always)), panes)
      assertEquals(panes.matching(Expr(never)), Vector.empty)
      assertEquals(
        panes.matching(Expr(never.and(explodes))),
        Vector.empty
      )
      assertEquals(panes.matching(Expr(always.or(explodes))), panes)
      val empty =
        Fields.toMany[JavaPane, JavaPane]("empty", _ => JList.of[JavaPane]())
      val absent = Fields.toOne[JavaPane, JavaPane](
        "absent",
        _ => Optional.empty[JavaPane]()
      )
      assertEquals(panes.matching(Expr(empty.any(always))), Vector.empty)
      assertEquals(panes.matching(Expr(empty.all(never))), panes)
      assertEquals(panes.matching(Expr(empty.none(always))), panes)
      assertEquals(panes.matching(Expr(absent.is(always))), Vector.empty)

      val original = Pane_.title().is("ScalaAlpha")
      val document = FilterJson.writeString(original, LibTmuxModels.pane())
      assert(document.contains("libtmux.filter/1"))
      val restored = FilterJson.readString(document, LibTmuxModels.pane())
      assertEquals(
        panes.matching(Expr(restored)),
        panes.filter(_.info.title == "ScalaAlpha")
      )
      Vector(
        document.replace("libtmux.filter/1", "libtmux.filter/2"),
        document.replace("pane_title", "unknown_field"),
        document.replace("equals", "unknown_operator")
      ).foreach { invalid =>
        intercept[SchemaException](
          FilterJson.readString(invalid, LibTmuxModels.pane())
        )
      }
      val relation = FilterJson.writeString(
        Window_.panes().any(original),
        LibTmuxModels.window()
      )
      intercept[SchemaException](
        FilterJson.readString(
          relation.replace("\"panes\"", "\"unknown_relation\""),
          LibTmuxModels.window()
        )
      )
      val forged =
        Fields.text[JavaPane]("pane_title", _ => "ScalaAlpha").is("ScalaAlpha")
      intercept[SchemaException](
        FilterJson.writeString(forged, LibTmuxModels.pane())
      )
      assertEquals(calls.get(), before)
    }
  }
}
