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
import io.github.libtmux.scaladsl.cats.{Control, Observation, Server}
import scala.concurrent.duration._

Server.resource[IO](config).use { server =>
  server.sessions.flatMap { sessions =>
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
            .filter(_.name() == renamed.info.name)
            .take(1)
            .compile.lastOrError.timeout(1.second)
          drops <- observation.droppedCount
          _ <- IO {
            assert(event.session() == session.info.id)
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
value. `droppedCount` reads the exact cumulative overflow count separately from
stream delivery; it cannot locate a gap between particular delivered values.
Closing a subscription discards queued values without counting them as
overflow.

FS2 demand does not make tmux obey backpressure, and the facade never mutes
pane output to imitate it. Read the counter when completeness matters. If it
increases, reacquire a snapshot before making decisions about current object
state. A snapshot cannot reconstruct the dropped terminal output. The
[ObserveChanges example](../examples/) demonstrates deliberate overflow and
state reconciliation.

## Cancellation and closure

An idle stream waits on an interruptible blocking worker in Java's `next`.
One active reader uses one such worker in addition to the attachment's Java
workers. Canceling a reader releases its consumer slot. Releasing the
observation closes its subscription and wakes a parked read.

Deliberate Scala observation or attachment closure ends the stream. If Java
ends the subscription for another reason, the adapter raises
`Observation.UnknownCause`: Java does not expose enough evidence to identify
the cause. Do not label every unexpected end as a timeout or a server crash.

Raw `Control.acknowledge` calls use bounded, supervised admission. Their
timeout begins after Scala admission. Canceling a genuinely dispatched request
can close the attachment and affect queued requests and observations. Use a
separate attachment when an observation must survive command cancellation.
An accepted reply is an acknowledgement, not proof that deferred tmux work
finished. See [execution](execution.md) for completion signals and uncertainty.

The [Control source][control] and [Observation source][observation] specify the
resource and stream boundaries.

[control]: ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Control.scala
[observation]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Observation.scala
