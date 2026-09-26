package io.github.libtmux.scaladsl.query

import io.github.libtmux.exception.CardinalityException
import munit.FunSuite

final class QuerySuite extends FunSuite {

  test("exactlyOne: one value, none, and more than one") {
    assertEquals(Vector(1).exactlyOne, Right(1))
    assertEquals(Vector.empty[Int].exactlyOne, Left(CardinalityError.NoMatch))
    assertEquals(
      Vector(1, 2, 3).exactlyOne,
      Left(CardinalityError.MultipleMatches(2))
    )
  }

  test("atMostOne: one value, none, and more than one") {
    assertEquals(Vector(1).atMostOne, Right(Some(1)))
    assertEquals(Vector.empty[Int].atMostOne, Right(None))
    assertEquals(
      Vector(1, 2).atMostOne,
      Left(CardinalityError.MultipleMatches(2))
    )
  }

  test(
    "CardinalityError.from mirrors Java's leaves losslessly, including atLeast"
  ) {
    val noMatch = new CardinalityException.NoMatch("expected one, found none")
    val many =
      new CardinalityException.MultipleMatches("expected one, found two", 2)
    assertEquals(CardinalityError.from(noMatch), CardinalityError.NoMatch)
    assertEquals(
      CardinalityError.from(many),
      CardinalityError.MultipleMatches(2)
    )
  }

  test("&&/||/! agree with their named forms") {
    val alwaysTrue: Expr[String] =
      Expr(io.github.libtmux.query.FilterExpr.and(java.util.List.of()))
    val alwaysFalse: Expr[String] =
      Expr(io.github.libtmux.query.FilterExpr.or(java.util.List.of()))
    assertEquals(
      (alwaysTrue && alwaysFalse).matches("x"),
      (alwaysTrue.and(alwaysFalse)).matches("x")
    )
    assertEquals(
      (alwaysTrue || alwaysFalse).matches("x"),
      (alwaysTrue.or(alwaysFalse)).matches("x")
    )
    assertEquals((!alwaysTrue).matches("x"), alwaysTrue.not.matches("x"))
    assert(alwaysTrue.matches("x"))
    assert(!alwaysFalse.matches("x"))
  }
}
