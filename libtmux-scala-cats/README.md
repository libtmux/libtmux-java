# libtmux-scala-cats

**Cats Effect resources and FS2 observations for `libtmux-scala`.**

This optional module adds scoped blocking execution and loss-aware control
observations. `libtmux-scala` does not depend on Cats Effect or FS2; add this
artifact only when an application uses those integrations. Use the same version
as `libtmux-scala`.

<!-- snippet: scala-build: cats-readme-install -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-cats" % "<version>"
```

From Gradle or Maven the coordinate is `io.github.libtmux:libtmux-scala-cats_3`.
None is on Maven Central yet; the Scala artifacts publish with the Java ones.

Every Scala block on this page is compiled for Scala 3.9 and run against a
real tmux server by the documentation suite.

**This project is alpha.** The API is not settled, and a release may change or
remove exported identifiers without a deprecation period. Pin an exact version
rather than a range. Not recommended for production.

## Read from a scoped server

Here `config` selects an existing tmux server. `Server.resource` owns the
client for the duration of `use` and the capture remains an ordinary vector.

<!-- snippet: scala-io: cats-list-active-panes -->
```scala
import _root_.cats.effect.IO
import io.github.libtmux.scaladsl.cats.{config => _, *}

Server.resource[IO](config).use { server =>
  server.panes().flatMap { panes =>
    val active = panes.filter(_.info.active())
    IO.println(active.map(_.info.currentCommand()).mkString("\n"))
  }
}
```

## Observe a session rename

Register the subscription and start its reader before changing tmux state.
Match the typed notification before acting on it. The stream suspends the
fiber, not a platform thread, while idle — `Async[F].async` over the
subscription's own `poll()`/`onReady()`, not a dedicated blocking pool.

<!-- snippet: scala-io: cats-observe-session-rename -->
```scala
import _root_.cats.effect.IO
import _root_.cats.syntax.all._
import io.github.libtmux.control.Notification
import io.github.libtmux.scaladsl.cats.{config => _, *}
import scala.concurrent.duration._

Server.resource[IO](config).use { server =>
  server.sessions().flatMap { sessions =>
    val session = sessions.head
    val name = "scala-cats-readme"
    Control.attach[IO](session).use { control =>
      control.events(16).use { observation =>
        for {
          received <- observation.stream
            .map(Observation.value)
            .unNone
            .map(_.notification())
            .collect { case event: Notification.SessionRenamed => event }
            .filter(event =>
              event.session().value() == session.info.id().value() && event.name() == name
            )
            .take(1)
            .compile
            .lastOrError
            .start
          renamed <- session.rename(name)
          event <- received.joinWithNever.timeout(1.second)
          _ <- IO(assert(event.session().value() == renamed.info.id().value()))
        } yield ()
      }
    }
  }
}
```

`events` has bounded buffering. A full buffer's next element is a gap.
`Observation.value` drops it. `Observation.kept` fails the read. Check
`droppedCount` and reacquire a snapshot when it increases; an event stream
cannot reconstruct dropped state. A subscription does not reconnect.

## Documentation

The [Scala guides](../docs/guide/scala/getting-started.md) cover installation,
[ownership](../docs/guide/scala/ownership.md),
[execution](../docs/guide/scala/execution.md) and
[streaming](../docs/guide/scala/streaming.md); the
[direct-style facade](../libtmux-scala/README.md) introduces the handles.
