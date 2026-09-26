# Streaming

The Cats module adapts Java control subscriptions to FS2. Acquire an attachment,
then an observation, then start the producer. An observation has one active
stream consumer; use FS2's explicit broadcast operations if several consumers
need the same values. Starting another reader on the same observation fails.

## Observe a state change

Here `config` selects an existing server with a session. The subscription is
registered before the rename. Evaluating the returned `IO` performs the work;
constructing it alone does not.

<!-- snippet: scala-io: streaming-session-rename -->
```scala
import _root_.cats.effect.IO
import io.github.libtmux.control.Notification
import io.github.libtmux.scaladsl.cats.{config => _, *}
import scala.concurrent.duration._

Server.resource[IO](config).use { server =>
  server.sessions().flatMap { sessions =>
    val session = sessions.head
    Control.attach[IO](session).use { control =>
      control.events(8).use { observation =>
        for {
          renamed <- session.rename("scala-streamed")
          event <- observation.stream
            .map(Observation.value)
            .unNone
            .map(_.notification())
            .collect { case value: Notification.SessionRenamed => value }
            .filter(_.name() == renamed.info.name())
            .take(1)
            .compile.lastOrError.timeout(1.second)
          drops <- observation.droppedCount
          _ <- IO {
            assert(event.session().value() == session.info.id().value())
            assert(drops == 0L)
          }
        } yield ()
      }
    }
  }
}
```

`events` retains Java's typed notifications and its unknown notification
variant. `output` yields pane-attributed `PaneOutput` values. Their `data` is
decoded terminal text, not exact bytes or a captured screen. A marker may span
several values; retain the necessary suffix while matching it.

## Loss and state reconciliation

Each subscription has a bounded queue. Overflow drops the oldest buffered
value. The stream then emits a `Delivery.Gap` ahead of what survived.
`Observation.value` drops that gap, so a pipeline that keeps only values
cannot see where the loss sat. `Observation.kept` fails the read instead.
`droppedCount` is the cumulative total.
Closing a subscription discards queued values without counting them as
overflow.

FS2 demand does not make tmux obey backpressure, and the facade never mutes
pane output to imitate it. Read the counter when completeness matters. If it
increases, reacquire a snapshot before making decisions about current object
state. A snapshot cannot reconstruct the dropped terminal output. The
[ObserveChanges example](../../../examples/src/main/scala/io/github/libtmux/scaladsl/examples/ObserveChanges.scala) demonstrates the live view's own
reconciliation over a real rename.

## Cancellation and closure

The Cats stream suspends the *fiber*, not a platform thread, while idle: it
polls first — nothing suspends when a step is already buffered — and only
arms the subscription's one-shot readiness callback (`onReady`) when nothing
is, disarming it (`clearReady`) if the fiber is cancelled first. No
`ExecutionContext` sized for blocking stream reads is needed, and no thread is
parked per subscription. Canceling a reader releases its consumer slot.
Releasing the observation closes its subscription and disarms any pending
wakeup.

Deliberate Scala observation or attachment closure ends the stream. If the
control client ends the subscription, the stream fails with that cause.
`Observation.UnknownCause` is only the remaining case: the subscription
ended, this side did not close it, and Java recorded no cause. Do not label
every unexpected end as a timeout or a server crash.

The direct-style module reads the same subscription through its own
`Observation`, blocking in Java's `next()` per read and guarding against a
second, overlapping `read` on the same instance with a scoped CAS — the
direct-style analogue of the Cats module's stream ownership.

Raw `Control.acknowledge` calls use bounded, supervised admission. Their
timeout begins after Scala admission. Canceling a genuinely dispatched request
can close the attachment and affect queued requests and observations. Use a
separate attachment when an observation must survive command cancellation.
An accepted reply is an acknowledgement, not proof that deferred tmux work
finished. See [execution](execution.md) for completion signals and uncertainty.

The [Control source][control] and [Observation source][observation] specify the
resource and stream boundaries.

[control]: ../../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Control.scala
[observation]:
  ../../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Observation.scala
