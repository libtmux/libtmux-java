package io.github.libtmux.scaladsl

import io.github.libtmux.exception._
import io.github.libtmux.transport.DispatchOutcome
import munit.FunSuite

/** Matches the real sealed `LibTmuxException` tree directly from Scala, with no
  * parallel Scala enum to keep in step with it. Type-test-plus-accessor, never
  * positional: a Java record synthesizes no Scala `unapply`.
  *
  * `classify` below is exhaustive over every one of the tree's eleven leaves,
  * checker-verified under this build's own `-Werror`: a missing case is a
  * compile error here, not a warning. Proving that bite is why this is a real
  * compilation unit and not `scala.compiletime.testing.typeCheckErrors` —
  * `typeCheckErrors` does not reliably honor a scoped language feature the way
  * a project-wide compiler flag does, so the honest test is this file compiling
  * at all, in this build, under `-Werror`. The break this proves: delete any
  * one `case` below and `core/compile` fails with `match may not be exhaustive`
  * turned into a hard error by `-Werror`, exactly as verified by hand while
  * writing this test (restored immediately after) — see the commit message for
  * the transcript.
  */
final class ExceptionMatchSuite extends FunSuite {

  private def classify(e: LibTmuxException): String = e match {
    case gone: TargetGoneException        => s"target gone: ${gone.getMessage}"
    case down: ServerUnavailableException =>
      s"server unavailable: ${down.getMessage}"
    case failed: DispatchException.Failed =>
      s"dispatch failed, safeToRetry=${failed.safeToRetry()}"
    case timedOut: DispatchException.TimedOut =>
      s"timed out, safeToRetry=${timedOut.safeToRetry()}"
    case ended: ControlEndedException =>
      s"control ended: ${ended.standardError()}"
    case unsupported: UnsupportedFeatureException =>
      s"unsupported: ${unsupported.getMessage}"
    case unencodable: UnencodableTextException =>
      s"unencodable: ${unencodable.getMessage}"
    case malformed: MalformedResponseException =>
      s"malformed response: ${malformed.getMessage}"
    case rejected: CommandRejectedException =>
      s"rejected: ${rejected.getMessage}"
    case _: CardinalityException.NoMatch            => "no match"
    case many: CardinalityException.MultipleMatches =>
      s"at least ${many.atLeast()} matches"
    // exhaustive over all eleven leaves; -Werror fails the build on a missing case, not just warns
  }

  test("every leaf of the sealed tree classifies, by type test plus accessor") {
    assertEquals(
      classify(new TargetGoneException("pane %3 is gone")),
      "target gone: pane %3 is gone"
    )
    assertEquals(
      classify(new ServerUnavailableException("no daemon")),
      "server unavailable: no daemon"
    )
    assertEquals(
      classify(
        new DispatchException.Failed(
          "boom",
          DispatchOutcome.NOT_DISPATCHED,
          null
        )
      ),
      "dispatch failed, safeToRetry=true"
    )
    assertEquals(
      classify(
        new DispatchException.TimedOut("slow", DispatchOutcome.UNKNOWN, null)
      ),
      "timed out, safeToRetry=false"
    )
    assertEquals(
      classify(new ControlEndedException("lost server", false, null)),
      "control ended: lost server"
    )
    assertEquals(
      classify(new UnsupportedFeatureException("needs tmux 3.4")),
      "unsupported: needs tmux 3.4"
    )
    assertEquals(
      classify(new UnencodableTextException("cannot encode in this locale")),
      "unencodable: cannot encode in this locale"
    )
    assertEquals(
      classify(new MalformedResponseException("row had 3 fields, expected 4")),
      "malformed response: row had 3 fields, expected 4"
    )
    assertEquals(
      classify(new CommandRejectedException("duplicate session")),
      "rejected: duplicate session"
    )
    assertEquals(
      classify(new CardinalityException.NoMatch("expected one, found none")),
      "no match"
    )
    assertEquals(
      classify(
        new CardinalityException.MultipleMatches("expected one, found two", 2)
      ),
      "at least 2 matches"
    )
  }

  test("ServerClosedException stays outside the sealed tree") {
    // ServerClosedException extends IllegalStateException, not LibTmuxException: the compiler
    // already refuses `closed: LibTmuxException`, which is the proof this leaves nothing for an
    // exhaustive LibTmuxException match to miss.
    val closed: IllegalStateException =
      new ServerClosedException(
        "scope is closed",
        DispatchOutcome.NOT_DISPATCHED
      )
    assertEquals(
      closed.asInstanceOf[ServerClosedException].outcome(),
      DispatchOutcome.NOT_DISPATCHED
    )
  }
}
