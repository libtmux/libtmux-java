package io.github.libtmux.scaladsl

import io.github.libtmux.{Pane => JavaPane, Pane_}
import io.github.libtmux.query.{FilterExpr, Operator, Selections}
import munit.FunSuite

final class QuerySuite extends FunSuite {
  test(
    "strict cardinality distinguishes absence and ambiguity without draining"
  ) {
    val value = new Object
    assert(Queries.exactlyOne(Vector(value)) eq value)
    assertEquals(Queries.oneOrNone(Vector(value)), Some(value))
    assertEquals(Queries.oneOrNone(Vector.empty[Int]), None)
    intercept[Selections.NoMatchException](
      Queries.exactlyOne(Vector.empty[Int])
    )
    intercept[Selections.MultipleMatchesException](
      Queries.exactlyOne(Vector(1, 2))
    )
    intercept[Selections.MultipleMatchesException](
      Queries.oneOrNone(Vector(1, 2))
    )
    def bounded = LazyList.cons(
      1,
      LazyList.cons(
        2,
        throw new AssertionError("cardinality drained beyond two matches")
      )
    )
    intercept[Selections.MultipleMatchesException](Queries.exactlyOne(bounded))
    intercept[Selections.MultipleMatchesException](Queries.oneOrNone(bounded))
  }

  test(
    "typed bridges reject wrong entities, detached data and invalid operators"
  ) {
    val valid: blocking.Pane => Boolean =
      Queries.panes(Pane_.index().atLeast(1))
    assert(valid != null)
    assert(compileErrors("""
      io.github.libtmux.scaladsl.Queries.panes(
        io.github.libtmux.Window_.name().is("wrong entity"))
    """).nonEmpty)
    assert(compileErrors("""
      val detached: io.github.libtmux.scaladsl.PaneInfo => Boolean =
        io.github.libtmux.scaladsl.Queries.panes(
          io.github.libtmux.Pane_.index().is(1))
    """).nonEmpty)
    assert(compileErrors("""
      io.github.libtmux.Pane_.index().startsWith("wrong operator")
    """).nonEmpty)
    assert(compileErrors("""
      io.github.libtmux.Window_.panes().any(
        io.github.libtmux.Session_.name().is("wrong relation target"))
    """).nonEmpty)
    intercept[IllegalArgumentException] {
      new FilterExpr.Compare[JavaPane, Integer](
        Pane_.index().ref(),
        Operator.EQUALS,
        java.lang.Long.valueOf(1)
      )
    }
    intercept[IllegalArgumentException] {
      new FilterExpr.Compare[JavaPane, Integer](
        Pane_.index().ref(),
        Operator.STARTS_WITH,
        "1"
      )
    }
  }
}
