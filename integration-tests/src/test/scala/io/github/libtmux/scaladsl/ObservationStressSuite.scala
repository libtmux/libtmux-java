package io.github.libtmux.scaladsl

import io.github.libtmux.control.{Delivery, PaneOutput}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import io.github.libtmux.scaladsl.streaming.{Observation => DirectObservation}
import munit.FunSuite
import scala.util.Using

/** Direct style's streaming path against a real, fast workload: a pane prints
  * many lines quickly, a small buffer forces overflow, and this counts what
  * arrived against what the pane actually printed. Reads through the blocking,
  * scoped-CAS [[DirectObservation]] — the direct-style equivalent of the Cats
  * module's own single-consumer guard
  * (`io.github.libtmux.scaladsl.cats.CatsObservationStressSuite` runs the same
  * workload over the `Async[F].async`/`poll`/`onReady` bridge). Events received
  * plus every gap's `missed` count must sum to at least the produced total.
  */
final class ObservationStressSuite extends FunSuite {

  val Lines = 20000
  val BufferCapacity =
    2 // deliberately tiny: tmux batches many lines per %output piece, so even a
  // small capacity needs to be this small to reliably force a real Delivery.Gap overflow.
  val Marker = "STRESS_DONE"

  def produce(pane: Pane): Unit =
    pane.sendLine(
      s"for i in $$(seq 1 $Lines); do echo line-$$i; done; echo $Marker"
    )

  test(
    "direct style: Observation's gap accounting is exact, and the marker survives overflow"
  ) {
    OwnedTmux.use { fixture =>
      Using.resource(Server.open(fixture.config)) { server =>
        val session = server.newSession("stress-direct")
        val pane = session.windows.head.panes.head
        produce(pane)

        val control = server.control(session)
        val sub = control.subscribeOutput(BufferCapacity)
        var events = 0L
        var gapMissed = 0L
        var gaps = 0L
        var sawMarker = false
        DirectObservation(sub).read(java.time.Duration.ofSeconds(10)) { steps =>
          steps.foreach {
            case event: Delivery.Event[PaneOutput @unchecked] =>
              events += 1
              if (event.value().data().contains(Marker)) {
                sawMarker = true
                sub.close()
              }
            case gap: Delivery.Gap[PaneOutput @unchecked] =>
              gaps += 1
              gapMissed += gap.missed()
          }
        }
        val dropped = sub.droppedCount()
        println(
          s"[direct] produced=${Lines + 1} lines, capacity=$BufferCapacity events=$events gaps=$gaps gapMissed=$gapMissed droppedCount=$dropped"
        )
        assert(
          gaps > 0,
          "a 2-capacity buffer against this workload must overflow at least once"
        )
        assertEquals(
          gapMissed,
          dropped,
          "every dropped event must show up as gap.missed somewhere in the stream"
        )
        assert(
          sawMarker,
          "the marker line must still arrive after a gap, not be lost with it"
        )
      }
    }
  }
}
