package io.github.libtmux.scaladsl.ox

import munit.FunSuite
import ox.*

/** The one worked `supervised`/`fork` scenario the coordinator's ruling asks
  * for, regardless of whether `libtmux-scala-ox` ships a `Flow`: two
  * direct-style-shaped calls run concurrently, and a failing one cancels its
  * sibling rather than leaking it. No library code of this project's own makes
  * that true — it is what `fork`/`supervised` already give any ordinary call
  * inside them, which is the whole reason to reach for Ox over two ad hoc
  * `Thread`s.
  */
final class ScenarioSuite extends FunSuite {

  test("both forks join when both succeed") {
    val (editor, logs) =
      Scenario.awaitBothReady(() => "editor ready", () => "logs ready")
    assertEquals(editor, "editor ready")
    assertEquals(logs, "logs ready")
  }

  test("a failing fork cancels its sibling instead of leaking or hanging it") {
    val sleptFully = new java.util.concurrent.atomic.AtomicBoolean(false)
    val sawFailure =
      try {
        supervised {
          val slow = fork {
            Thread.sleep(500)
            sleptFully.set(true)
            "should never get here"
          }
          val fast = fork[String] {
            Thread.sleep(5)
            throw new RuntimeException("pane crashed")
          }
          (slow.join(), fast.join())
        }
        false
      } catch {
        case e: RuntimeException if e.getMessage == "pane crashed" => true
      }
    assert(
      sawFailure,
      "a failing fork must cancel its sibling and propagate, not hang or swallow it"
    )
    assert(
      !sleptFully.get(),
      "the cancelled sibling must not have run to completion"
    )
  }
}
