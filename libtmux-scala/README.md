<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Scala">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-scala

**Scala 3 collections and opaque handles over libtmux for Java.**

</div>

Inspect and operate tmux through libtmux for Java with Scala collections.
[`Server.panes`][server] returns an immutable `Vector` of handles, which are
opaque aliases of the Java ones. The [Cats module][cats] adds scoped effects.

`io.github.libtmux:libtmux-scala_3` — the Scala artifacts are on Maven Central
and release with the Java ones.
API reference: [libtmux.org][reference] for the current trunk,
[javadoc.io][javadoc] for a released version.

> **Alpha.** Releases carry an `-alpha` prerelease tag. The API is not settled,
> and any release may change or remove exported identifiers without a
> deprecation period. Pin an exact version. Not recommended for production.

**Contents** — [Quick start](#quick-start) ·
[What it leaves out](#what-it-leaves-out) · [Support](#support-and-status) ·
[Documentation](#documentation)

## Quick start

<!-- snippet: scala-build: readme-install -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala" % "<version>"
```

From Gradle or Maven the coordinate is `io.github.libtmux:libtmux-scala_3`. The
version is always `libtmux`'s own. Core depends on `libtmux` and the Scala 3
library only; Cats, FS2 and Ox arrive through
[`libtmux-scala-cats`](../libtmux-scala-cats/README.md) and
[`libtmux-scala-ox`](../libtmux-scala-ox/README.md).

### [`Server.panes`][server]: inspect captured panes

Start with [a first
client](../docs/guide/scala/getting-started.md#a-first-client) to build a
`ServerConfig` from your tmux executable, socket and configuration file. Here
`config` selects an existing server, and opening the client does not create a
session:

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

It runs against a real tmux. The operation closes its owned client and leaves
the daemon running.

- [`Server.panes()`][server] acquires state. Reading `info`, traversing
  [`Pane.window`][pane], or filtering the captured vector performs no further
  tmux commands. A [`Window`][window] reaches its [`Session`][session] the same
  way.
- `refresh()` returns a new capture.
- A failed acquisition raises the original Java error. It does not become an
  empty vector.

## What it leaves out

- **A Scala 2.13 build.** There is none.
- **Effects in the core.** Cats Effect and FS2 live in
  [`libtmux-scala-cats`](../libtmux-scala-cats/README.md), and Ox in
  [`libtmux-scala-ox`](../libtmux-scala-ox/README.md).
- **A parallel type.** The handles are opaque aliases of the Java ones.

## Support and status

- **Scala 3.9 and JDK 25 or newer.**
- **tmux 3.2a through 3.7c**, which the Java library supports and every tmux
  lane in CI runs these suites against.
  [Compatibility](../docs/guide/scala/compatibility.md) has the detail.
- **Alpha.** Pin an exact version rather than a range.

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
- [Examples](../examples/): runnable programs, each executed against a real
  tmux.

The [Java guide](../docs/guide/scala.md) shows direct Java use without the
facade. Source contracts live beside [direct-style operations][source-server]
and [Cats resources][cats-server]. For changes to existing APIs, see the
repository's [migration notes](../MIGRATION.md).

[cats]: ../libtmux-scala-cats/README.md
[cats-server]:
  ../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
[javadoc]: https://javadoc.io/doc/io.github.libtmux/libtmux-scala_3
[pane]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-pane/
[reference]: https://libtmux.org/en/scala/latest/reference/
[server]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-server/
[session]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-session/
[source-server]:
  src/main/scala/io/github/libtmux/scaladsl/Server.scala
[window]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-window/
