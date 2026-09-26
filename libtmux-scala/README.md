# libtmux-scala

**Scala 3 collections and opaque handles over libtmux for Java.**

Use Scala collections and explicit effects to inspect and operate tmux through
libtmux for Java. The direct-style facade's handles are opaque aliases of the
Java ones — lossless by construction, never a hand-copied parallel type — and
return immutable `Vector` and `Option` values. The
[separate Cats module](../libtmux-scala-cats/README.md) supplies scoped
effects and FS2 observations. Both retain the original Java handles, targeting
rules and command engine.

**This project is alpha.** Releases carry an `-alpha` prerelease tag. The API is
not settled, and any release may change or remove exported identifiers without a
deprecation period. Pin an exact version rather than a range. Not recommended
for production.

## Requirements

Scala 3.9 and JDK 25 or newer; there is no Scala 2.13 build. tmux 3.2a through
3.7c, which the Java library supports and every tmux lane in CI runs these
suites against. See [Compatibility](../docs/guide/scala/compatibility.md).

## Installation

<!-- snippet: scala-build: readme-install -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala" % "<version>"
```

From Gradle or Maven the coordinate is `io.github.libtmux:libtmux-scala_3`.
The version is always `libtmux`'s own: the Scala artifacts publish with the Java
ones, starting with the first release that includes them, and none is on Maven
Central yet. Core depends on `libtmux` and the Scala 3 library only; Cats, FS2
and Ox arrive through [`libtmux-scala-cats`](../libtmux-scala-cats/) and
[`libtmux-scala-ox`](../libtmux-scala-ox/).

## Inspect captured panes

Start with [a first client](../docs/guide/scala/getting-started.md#a-first-client) to construct
`ServerConfig` from your tmux executable, owned socket and configuration file.
Here `config` selects an existing server. Opening the client does not create a
session. This operation closes its owned client while leaving the daemon
running.

<!-- snippet: scala-sync: readme-inspect-panes -->
```scala
import io.github.libtmux.scaladsl.{config => _, *}
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val named = panes.filter(_.info.title().nonEmpty)
  val commands = named.map(_.info.currentCommand())
  assert(commands.size <= panes.size)
  assert(panes.forall(p => p.window.info.context().equals(p.info.context())))
}
```

`server.panes()` acquires state. Reading `info`, traversing `window`, or
filtering the captured vector performs no further tmux commands. `refresh()`
returns a new capture. A failed acquisition raises the original Java error;
it does not become an empty vector.

## Documentation

- [Getting started](../docs/guide/scala/getting-started.md): installation, a
  first owned client, and building from source.
- [Queries](../docs/guide/scala/query.md): native collections, the typed field
  DSL and strict cardinality.
- [Ownership](../docs/guide/scala/ownership.md): captured handles, borrowing and
  cleanup.
- [Execution](../docs/guide/scala/execution.md): direct-style calls, bounded
  effects and command outcomes.
- [Streaming](../docs/guide/scala/streaming.md): subscriptions, cancellation and
  visible loss.
- [Compatibility](../docs/guide/scala/compatibility.md): compilers, runtimes and
  what CI runs.
- [Examples](../examples/): runnable programs, each executed against a real tmux.

The [Java guide](../docs/guide/scala.md) shows direct Java use without the
facade. Source contracts live beside [direct-style operations][server]
and [Cats resources][cats-server]. For changes to existing APIs, see the
repository's [migration notes](../MIGRATION.md).

[server]:
  src/main/scala/io/github/libtmux/scaladsl/Server.scala
[cats-server]:
  ../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
