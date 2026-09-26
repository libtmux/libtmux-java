package io.github.libtmux.scaladsl

import io.github.libtmux.transport.{CommandResult => JavaResult}
import java.util.{List, OptionalInt}
import munit.FunSuite
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** `transport.CommandResult` and `PaneRun` are Java's own records, used
  * directly, so this pins `OptionalInt`/`List` boundary conversions against the
  * real types.
  */
final class CommandResultSuite extends FunSuite {
  test("raw failures remain data and preserve every supplied output row") {
    val result =
      new JavaResult(7, List.of("first", "", ""), List.of("problem", ""))
    assertEquals(result.exitCode(), 7)
    assertEquals(result.stdout().asScala.toVector, Vector("first", "", ""))
    assertEquals(result.stderr().asScala.toVector, Vector("problem", ""))
    assert(!result.succeeded())
    val run = new io.github.libtmux.PaneRun(
      io.github.libtmux.PaneRun.Outcome.TIMED_OUT,
      OptionalInt.empty(),
      List.of("partial", ""),
      false
    )
    assertEquals(run.exitStatus().toScala, None)
    assertEquals(run.output().asScala.toVector, Vector("partial", ""))
    assert(!run.exact())
    assert(!run.succeeded())
  }
}
