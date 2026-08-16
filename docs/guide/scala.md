# Scala

## There is nothing to install

`io.github.libtmux:libtmux` is a Java artifact and carries no Scala binary-version
suffix, which is exactly what makes it usable from every Scala version at once.
Depend on it with a single `%`, never `%%`:

<!-- snippet: skip: build configuration, not library code -->
```scala
libraryDependencies += "io.github.libtmux" % "libtmux" % "0.0.1-alpha.5"
```

`%%` would ask for `libtmux_3`, which does not exist and should not. A Java
artifact published under a Scala suffix is a packaging bug, not a convenience.

## Crossing the two collection worlds

The API returns `java.util.List` and `java.util.Optional`, which one import
converts:

<!-- snippet: skip: no Scala module builds here; see the section below -->
```scala
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

val editors = server.windows.asScala.filter(_.name.startsWith("edit")).toSeq
val active  = session.activeWindow.toScala   // Option[Window]
```

Filters are `java.util.function.Predicate`, so a Scala lambda works where one is
expected, and a `FilterExpr` drops into `filter` unchanged:

<!-- snippet: skip: no Scala module builds here; see the section below -->
```scala
server.panes.asScala.filter(Pane_.command.startsWith("nvim").test).toSeq
```

## Why there is no libtmux-scala

There could be one, and the shape it would take is already decided so that
nobody has to rediscover it:

**It would be a separate sbt build**, consuming the published Java artifact,
cross-published as `libtmux-scala_2.13` and `libtmux-scala_3`.

**It would not be a Gradle module.** Gradle's Scala plugin does not append the
binary-version suffix to published artifacts, has no cross-build loop, and has
no equivalent of sbt's conflicting-cross-version-suffix detection — so the
failure mode of getting it wrong is `_2.13` and `_3` copies of the same library
resolving quietly onto one classpath rather than a build error.

Until someone wants that enough to maintain a second build tool in this
repository, the Java API is the Scala API, and it is a complete one.
