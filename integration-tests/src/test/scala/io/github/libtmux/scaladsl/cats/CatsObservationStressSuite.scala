package io.github.libtmux.scaladsl.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import io.github.libtmux.control.{Delivery, PaneOutput}
import io.github.libtmux.scaladsl.fixture.OwnedTmux
import munit.FunSuite
import java.util.concurrent.atomic.AtomicLong

/** The Cats module's streaming path against the same real, fast workload
  * `io.github.libtmux.scaladsl.ObservationStressSuite` runs for direct style: a
  * pane prints many lines quickly, a small buffer forces overflow. This reads
  * through `Async[F].async` over `poll()`/`onReady()` (`Observation.stream`) —
  * no dedicated blocking pool, no thread parked per subscription — and counts
  * events plus every gap's `missed` against what the pane actually printed.
  */
final class CatsObservationStressSuite extends FunSuite {

  val Lines = 20000
  val BufferCapacity =
    2 // deliberately tiny: tmux batches many lines per %output piece, so even a
  // small capacity needs to be this small to reliably force a real Delivery.Gap overflow.
  val Marker = "STRESS_DONE"

  def produce(pane: io.github.libtmux.Pane): Unit =
    pane.sendLine(
      s"for i in $$(seq 1 $Lines); do echo line-$$i; done; echo $Marker"
    )

  test(
    "Cats: the poll/onReady fs2 bridge's gap accounting is exact, the marker survives overflow"
  ) {
    OwnedTmux.use { fixture =>
      val events = new AtomicLong(0)
      val gaps = new AtomicLong(0)
      val gapMissed = new AtomicLong(0)
      var dropped = 0L
      val program = Server.resource[IO](fixture.config).use { server =>
        for {
          session <- server.newSession("stress-cats")
          // Session.windows/Window.panes are CAPTURED: pure, not wrapped in F.
          window = session.windows.head
          pane = window.panes.head
          _ <- IO(produce(pane.underlying.asJava))
          _ <- Control.attach(session).use { control =>
            control.output(BufferCapacity).use { obs =>
              obs.stream
                .evalMap { step =>
                  IO {
                    step match {
                      case event: Delivery.Event[PaneOutput @unchecked] =>
                        events.incrementAndGet()
                        event.value().data().contains(Marker)
                      case gap: Delivery.Gap[PaneOutput @unchecked] =>
                        gaps.incrementAndGet()
                        gapMissed.addAndGet(gap.missed())
                        false
                    }
                  }
                }
                .takeThrough(done => !done)
                .compile
                .drain *> obs.droppedCount.map(d => dropped = d)
            }
          }
        } yield ()
      }
      program.unsafeRunSync()
      println(
        s"[cats] produced=${Lines + 1} lines, capacity=$BufferCapacity events=${events.get()} gaps=${gaps.get()} gapMissed=${gapMissed.get()} droppedCount=$dropped"
      )
      assert(
        gaps.get() > 0,
        "a 2-capacity buffer against this workload must overflow at least once"
      )
      assertEquals(
        gapMissed.get(),
        dropped,
        "every dropped event must show up as gap.missed somewhere in the stream"
      )
    }
  }
}
