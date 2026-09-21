# Scala

Scala callers can use the Java API directly or the
[Scala facade](../../libtmux-scala/README.md). The facade supplies native collections,
optional values and a separate Cats/FS2 adapter. It is an independent sbt build
with `_2.13` and `_3` artifacts; see its installation and verification status
before selecting a dependency.

## Direct Java dependency

The Java artifact has no Scala binary-version suffix. Use a single `%`:

<!-- snippet: scala-build: install-direct-java -->
```sbt
libraryDependencies += "io.github.libtmux" % "libtmux" % "0.0.1-alpha.14"
```

Use `%%` for the separate `libtmux-scala` or `libtmux-scala-cats` artifact.
It must not be used for `libtmux` itself. The
[facade getting-started guide](../../libtmux-scala/docs/getting-started.md) documents
its local development prerequisites separately from released Java coordinates.

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

The separate Scala build compiles and runs this example on both producer
families. See its [query guide](../../libtmux-scala/docs/query.md) for facade predicate
adapters and strict cardinality, and its
[ownership guide](../../libtmux-scala/docs/ownership.md) before borrowing Java clients.
