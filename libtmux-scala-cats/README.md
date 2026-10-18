<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Scala">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-scala-cats

**Cats Effect resources and FS2 observations for `libtmux-scala`.**

</div>

[`Server.resource`][server] owns a client for the length of a `use`, and
[`Control.attach`][control] observes tmux without blocking a thread. This
optional module adds scoped execution and loss-aware control observations.

`libtmux-scala` does not depend on Cats Effect or FS2.

`io.github.libtmux:libtmux-scala-cats_3` — on Maven Central, released with the
Java artifacts. Use the same version as `libtmux-scala`.
API reference: [libtmux.org][reference] for the current trunk,
[javadoc.io][javadoc] for a released version.

> **Alpha.** The API is not settled, and a release may change or remove
> exported identifiers without a deprecation period. Pin an exact version
> rather than a range. Not recommended for production.

**Contents** — [Quick start](#quick-start) · [Observe](#observe-tmux-change) ·
[What it leaves out](#what-it-leaves-out) · [Support](#support-and-status)

## Quick start

<!-- snippet: scala-build: cats-readme-install -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-cats" % "<version>"
```

From Gradle or Maven the coordinate is `io.github.libtmux:libtmux-scala-cats_3`.

### [`Server.resource`][server]: read from a scoped server

Here `config` selects an existing tmux server. `Server.resource` owns the client
for the duration of `use`, and the capture remains an ordinary vector:

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

It compiles for Scala 3.9 and runs against a real tmux in the documentation
suite. It prints the current command of each active pane.

## Observe tmux change

### [`Control.attach`][control]: observe a session rename

Register the subscription and start its reader before changing tmux state, and
match the typed [`Notification`][notification] before acting on it:

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

While idle, the stream suspends the fiber and holds no platform thread. It is
`Async[F].async` over the subscription's own `poll()` and `onReady()`, and uses
no blocking pool.

### [`Observation`][observation]: a gap is an element

`events` has bounded buffering, and a full buffer's next element is a gap:

- `Observation.value` drops the gap.
- `Observation.kept` fails the read.
- Check `droppedCount` and reacquire a snapshot when it increases. An event
  stream cannot reconstruct dropped state.
- A subscription does not reconnect.

## Subsystem handles

These return a small wrapper class parameterized by `F[_]`, never the raw Java
handle. Their own reads and mutations run through this server's `Execution` like
every other Cats operation, admission-bounded and cancellable:

- [`Server.hooks`][hooks], [`Pane.options`][options], [`Server.shell`][shell]
- [`Server.commands`][commands], [`Server.buffers`][buffers],
  [`Session.environment`][environment]
- [`Server.messageLog`][message-log], [`Server.prompt`][prompt],
  [`Server.keys`][keys]

The direct-style facade returns the raw Java handle for these, since its calls
block.

## What it leaves out

- **Cats Effect and FS2 in the core.** Add this artifact only when an
  application uses those integrations.
- **Reconnecting subscriptions.** A subscription does not reconnect.
- **Reconstructing dropped events.** A gap says how many were lost; reacquire a
  snapshot instead.

## Support and status

- **Scala 3.9**, and the same JDK and tmux range as
  [`libtmux-scala`](../libtmux-scala/README.md).
- **Alpha.** Pin an exact version rather than a range.

## Documentation

- The [Scala guides](../docs/guide/scala/getting-started.md) cover installation,
  [ownership](../docs/guide/scala/ownership.md),
  [execution](../docs/guide/scala/execution.md) and
  [streaming](../docs/guide/scala/streaming.md).
- The [direct-style facade](../libtmux-scala/README.md) introduces the handles.

[buffers]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-buffers/
[commands]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-commands/
[control]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-control/
[environment]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-environment/
[hooks]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-hooks/
[javadoc]: https://javadoc.io/doc/io.github.libtmux/libtmux-scala-cats_3
[keys]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-keys/
[message-log]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-messagelog/
[notification]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-notification-notification/
[observation]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-observation/
[options]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-options/
[prompt]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-prompt/
[reference]: https://libtmux.org/en/scala/latest/reference/
[server]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-server/
[shell]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-shell/
