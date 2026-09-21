# libtmux for Scala

Use Scala collections and explicit effects to inspect and operate tmux through
libtmux for Java. The blocking facade returns immutable `Vector` and `Option`
values. The separate Cats module supplies scoped effects and FS2 observations.
Both retain the original Java handles, targeting rules and command engine.

**This project is alpha.** A future Scala release will carry an `-alpha`
prerelease tag. The API is not settled, and any release may change or remove
exported identifiers without a deprecation period. Pin an exact version rather
than a range. Not recommended for production.

## Requirements

The build targets Scala 2.13.18 and 3.3.8 with JDK 21 bytecode. Scala 3.9.0 is
a downstream consumer target. Java supplies support for tmux 3.2a through 3.7c;
the Scala operating-system and runtime checks are listed in
[Compatibility](docs/compatibility.md). A target is not a completed test result.

## Installation

The `libtmux-scala/sbtw` wrapper starts the shared sbt build. These commands
use local staging; they do not establish a published Scala release. The
[getting-started guide](docs/getting-started.md) stages the Java prerequisite
and all four Scala artifacts before resolving a consumer.

| Artifact | Purpose |
| --- | --- |
| `libtmux-scala_2.13`, `libtmux-scala_3` | Blocking and query APIs |
| `libtmux-scala-cats_2.13`, `libtmux-scala-cats_3` | Resources and streams |

All coordinates use group `io.github.libtmux`. Use `%` for the unsuffixed Java
artifact and `%%` for a Scala artifact. Core does not depend on Cats, FS2,
Jackson, JUnit, Kotlin, MCP or workspace packages.

## Inspect captured panes

Start with [a first client](docs/getting-started.md#a-first-client) to construct
`ServerConfig` from your tmux executable, owned socket and configuration file.
Here `config` selects an existing server. Opening the client does not create a
session. This operation closes its owned client while leaving the daemon
running.

<!-- snippet: scala-sync: readme-inspect-panes -->
```scala
import io.github.libtmux.scaladsl.blocking.Server
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val named = panes.filter(_.info.title.nonEmpty)
  val commands = named.map(_.info.currentCommand)
  assert(commands.size <= panes.size)
  assert(panes.forall(p => p.window.info.context == p.info.context))
}
```

`server.panes()` acquires state. Reading `info`, traversing `window`, or
filtering the captured vector performs no further tmux commands. `refresh()`
returns a new capture. A failed acquisition raises the original Java error;
it does not become an empty vector.

## Documentation

- [Getting started](docs/getting-started.md): build, staged dependencies and a
  first owned client.
- [Queries](docs/query.md): native collections, strict cardinality and typed
  Java expressions.
- [Ownership](docs/ownership.md): captured handles, borrowing and cleanup.
- [Execution](docs/execution.md): blocking calls, bounded effects and command
  outcomes.
- [Streaming](docs/streaming.md): subscriptions, cancellation and visible loss.
- [Compatibility](docs/compatibility.md): producer, consumer and runtime gates.
- [Examples](examples/): executed orchestration programs.

The [Java guide](../docs/guide/scala.md) shows direct Java use without the
facade. Source contracts live beside [blocking operations][blocking-server]
and [Cats resources][cats-server]. For changes to existing APIs, see the
repository's [migration notes](../MIGRATION.md).

## Status

The implementation and verification are in progress. Local checks do not prove
the full compatibility matrix or publication readiness. Releases carry an
`-alpha` prerelease tag. The API is not settled, and any release may change or
remove exported identifiers without a deprecation period. Pin an exact version
rather than a range. Not recommended for production.

[blocking-server]:
  src/main/scala/io/github/libtmux/scaladsl/blocking/Server.scala
[cats-server]:
  ../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
