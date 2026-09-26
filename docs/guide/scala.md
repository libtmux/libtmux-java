# Scala

Scala callers can use the Java API directly or the
[Scala facades](../../libtmux-scala/README.md): native collections, optional
values and opaque handles, with separate Cats/FS2 and Ox modules. They target
Scala 3.9 only and publish `_3` artifacts with the Java ones. Start with
[getting started](scala/getting-started.md), then [queries](scala/query.md),
[ownership](scala/ownership.md), [execution](scala/execution.md),
[streaming](scala/streaming.md) and [compatibility](scala/compatibility.md).

Runnable programs for both facades live in
[`examples/`](../../examples/): blocking workspace
operations, bounded concurrent capture, notification loss and reconciliation,
and Cats Effect resource ownership and cancellation.

## Direct Java dependency

The Java artifact has no Scala binary-version suffix. Use a single `%`:

<!-- snippet: scala-build: install-direct-java -->
```sbt
libraryDependencies += "io.github.libtmux" % "libtmux" % "0.0.1-alpha.14"
```

Use `%%` for the Scala artifacts (`libtmux-scala`, `libtmux-scala-cats`,
`libtmux-scala-ox`), never for `libtmux` itself.

## Java collections and optional values

Here `config` is a Java `ServerConfig` selecting an explicit binary and a
server endpoint. Conversion adapters do not establish ownership of a Java
collection. Finish with `toVector` to retain an immutable Scala sequence.

<!-- snippet: scala-sync: direct-java-captured-panes -->
```scala
import io.github.libtmux.{Pane_, Server}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes().asScala.toVector
  val expression = Pane_.command().startsWith("cat")
  val selected = panes.filter(expression.test)
  val missing = server.session("scala-direct-missing").toScala
  assert(selected.forall(_.currentCommand().startsWith("cat")))
  assert(missing.isEmpty)
}
```

`Using.resource` closes this client without killing tmux. Filtering the
acquired Java handles is local. A failed live read still throws; it does not
become an empty Scala collection or `None`.

The documentation suite compiles and runs this example against a real tmux.
See the [query guide](scala/query.md) for facade predicate adapters and strict
cardinality, and the [ownership guide](scala/ownership.md) before borrowing
Java clients.
