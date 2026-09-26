package io.github.libtmux.scaladsl.query

import io.github.libtmux.exception.CardinalityException

/** Mirrors [[CardinalityException]]'s two leaves losslessly, including the
  * match count Java's own `MultipleMatches.atLeast()` carries — dropping it
  * would be exactly the hand-authored lossy projection this facade's opaque
  * handles exist to make impossible everywhere else.
  */
enum CardinalityError derives CanEqual {
  case NoMatch
  case MultipleMatches(atLeast: Int)
}

object CardinalityError {
  def from(e: CardinalityException): CardinalityError = e match {
    case _: CardinalityException.NoMatch            => CardinalityError.NoMatch
    case many: CardinalityException.MultipleMatches =>
      CardinalityError.MultipleMatches(many.atLeast())
  }
}

extension [A](values: IterableOnce[A]) {

  /** The single match, or a failure naming which way the count was wrong.
    * Single-pass: mirrors `Selections.exactlyOne`'s early exit, stopping at the
    * second match.
    */
  def exactlyOne: Either[CardinalityError, A] = {
    val it = values.iterator
    if (!it.hasNext) Left(CardinalityError.NoMatch)
    else {
      val first = it.next()
      if (it.hasNext) Left(CardinalityError.MultipleMatches(atLeast = 2))
      else Right(first)
    }
  }

  /** The single match if there is one, `None` if there is none — still a
    * failure for several.
    */
  def atMostOne: Either[CardinalityError, Option[A]] = {
    val it = values.iterator
    if (!it.hasNext) Right(None)
    else {
      val first = it.next()
      if (it.hasNext) Left(CardinalityError.MultipleMatches(atLeast = 2))
      else Right(Some(first))
    }
  }
}
