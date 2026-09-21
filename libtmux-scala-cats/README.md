# libtmux-scala-cats

**Cats Effect resources and FS2 observations for `libtmux-scala`.**

This optional module adds scoped blocking execution and loss-aware control
observations. `libtmux-scala` does not depend on Cats Effect or FS2; add this
artifact only when an application uses those integrations. Use the same version
as `libtmux-scala`.

**This project is alpha.** The API is not settled, and a release may change or
remove exported identifiers without a deprecation period. Pin an exact version
rather than a range. Not recommended for production.

## Read from a scoped server

Here `config` selects an existing tmux server. `Server.resource` owns the
client for the duration of `use` and the capture remains an ordinary vector.

<!-- snippet: scala-io: cats-list-active-panes -->
```scala
import _root_.cats.effect.IO
import io.github.libtmux.scaladsl.cats.Server

Server.resource[IO](config).use { server =>
  server.panes.flatMap { panes =>
    val active = panes.filter(_.info.active)
    IO.println(active.map(_.info.currentCommand).mkString("\n"))
  }
}
```

## Observe a session rename

Register the subscription and start its reader before changing tmux state.
Match the typed notification before acting on it.

<!-- snippet: scala-io: cats-observe-session-rename -->
```scala
import _root_.cats.effect.IO
import _root_.cats.syntax.all._
import io.github.libtmux.control.Notification
import io.github.libtmux.scaladsl.cats.{Control, Observation, Server}
import scala.concurrent.duration._

Server.resource[IO](config).use { server =>
  server.sessions.flatMap { sessions =>
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
              event.session() == session.info.id && event.name() == name
            )
            .take(1)
            .compile
            .lastOrError
            .start
          renamed <- session.rename(name)
          event <- received.joinWithNever.timeout(1.second)
          _ <- IO(assert(event.session() == renamed.info.id))
        } yield ()
      }
    }
  }
}
```

`events` has bounded buffering. A full buffer's next element is a gap. Check
`droppedCount` and reacquire a snapshot when it increases; an event stream
cannot reconstruct dropped state. A subscription does not reconnect.

## Documentation

The [Scala facade guide](../libtmux-scala/README.md) covers installation,
resource ownership, execution, and streaming contracts.
