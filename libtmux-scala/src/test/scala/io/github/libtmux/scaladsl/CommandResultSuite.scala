package io.github.libtmux.scaladsl

import io.github.libtmux.transport.{CommandResult => JavaResult}
import java.util.{List, OptionalInt}
import munit.FunSuite

final class CommandResultSuite extends FunSuite {
  test("raw failures remain data and preserve every supplied output row") {
    val original =
      new JavaResult(7, List.of("first", "", ""), List.of("problem", ""))
    val result = CommandResult.fromJava(original)
    assertEquals(result.exitCode, 7)
    assertEquals(result.stdout, Vector("first", "", ""))
    assertEquals(result.stderr, Vector("problem", ""))
    assert(!result.succeeded)
    assert(result.asJava eq original)
    val run = PaneRun.fromJava(
      new io.github.libtmux.PaneRun(
        io.github.libtmux.PaneRun.Outcome.TIMED_OUT,
        OptionalInt.empty(),
        List.of("partial", ""),
        false
      )
    )
    assertEquals(run.exitStatus, None)
    assertEquals(run.output, Vector("partial", ""))
    assert(!run.exact)
    assert(!run.succeeded)
  }
}
