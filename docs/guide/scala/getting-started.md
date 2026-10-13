# Getting started

The Scala facades need Scala 3.9 and JDK 25 or newer, and tmux 3.2a through
3.7c on the machine they drive.

## Install

Three artifacts, all in group `io.github.libtmux` and all at the same version
as `libtmux` itself. `%%` adds the `_3` suffix that marks a Scala 3 artifact.
They are on Maven Central and release with the Java artifacts.

- **`libtmux-scala_3`** — direct-style handles, collections and the typed query
  DSL.
- **`libtmux-scala-cats_3`** — Cats Effect resources and FS2 observations.
- **`libtmux-scala-ox_3`** — an Ox `Flow` over subscriptions and live views.

For a new sbt project, save this as `build.sbt`:

<!-- snippet: scala-build: install-core -->
```sbt
scalaVersion := "3.9.0"
scalacOptions += "-release:25"
libraryDependencies += "io.github.libtmux" %% "libtmux-scala" % "0.0.1-alpha.17"
```

For Cats Effect and FS2, or for Ox, add the matching module:

<!-- snippet: scala-build: install-cats -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-cats" % "0.0.1-alpha.17"
```

<!-- snippet: scala-build: install-ox -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-ox" % "0.0.1-alpha.17"
```

From Gradle or Maven, name the suffixed artifact directly:
`io.github.libtmux:libtmux-scala_3:0.0.1-alpha.17`. The core facade depends on
`libtmux` and the Scala 3 library, and on nothing else.

## A first client

Save this complete program as `src/main/scala/Main.scala`. It selects a fresh
socket under `/tmp/libtmux-java-dev/`, creates a session with two panes, checks
the captured layout, and stops its private daemon before closing the client.
It reads tmux from `PATH`; set `LIBTMUX_TMUX` to select another binary.

<!-- snippet: scala-main: getting-started-session -->
```scala
import io.github.libtmux.{
  Layout, ServerConfig, ServerEndpoint, SessionSpec, SplitSpec
}
import io.github.libtmux.scaladsl.*
import java.nio.file.{Files, Path}
import scala.util.Using

object Main {
  def main(args: Array[String]): Unit = {
    val root = Files.createDirectories(Path.of("/tmp/libtmux-java-dev"))
    val directory = Files.createTempDirectory(root, "scala-start-")
    val socket = directory.resolve("s")
    val config = ServerConfig.builder()
      .binary(sys.env.getOrElse("LIBTMUX_TMUX", "tmux"))
      .endpoint(ServerEndpoint.socketPath(socket))
      .configFile(Path.of("/dev/null"))
      .build()
    try {
      Using.resource(Server.open(config)) { server =>
        try {
          val session = server.newSession(
            SessionSpec.builder().named("scala-start").running("cat", "-").build()
          )
          val window = session.windows.head
          val second = window.split(
            SplitSpec.builder().running("cat", "-").build()
          )
          window.selectLayout(Layout.EVEN_HORIZONTAL)
          second.select()
          val panes = window.refresh().panes
          assert(panes.size == 2)
          assert(panes.exists(_.info.id().value() == second.info.id().value()))
          println(s"created ${panes.size} panes")
        } finally server.killServer()
      }
    } finally {
      Files.deleteIfExists(socket)
      Files.deleteIfExists(directory)
    }
  }
}
```

Run it with sbt:

```console
$ sbt run
```

The output includes `created 2 panes`. `window.refresh()` captures the pane
list after the split; the original window's captured list still has one pane.
`Using.resource` closes the library client. The separate `killServer` call
stops the daemon because this program created and owns it. When connecting to
an existing daemon, leave its lifetime with its owner and remove only sessions
your application created.

Timeouts are `scala.concurrent.duration.FiniteDuration` at every public entry
point, converted once at the boundary: `pane.awaitText("$", 5.seconds)`
never surfaces `java.time.Duration` to the caller.

Follow with [queries](query.md), [ownership](ownership.md), then
[execution](execution.md). The [API reference][server] documents the direct-style server.

## Build from source

The facades build with the rest of the repository, from its root:

```console
$ ./gradlew :libtmux-scala:check :libtmux-scala-cats:check :libtmux-scala-ox:check
```

The real-tmux suites run with the Java ones in `integration-tests`:

```console
$ ./gradlew :integration-tests:test --tests 'io.github.libtmux.scaladsl.*'
```

Format Scala sources before committing:

```console
$ ./gradlew spotlessApply
```

The API documentation, with a browser for every source it documents, generated
operations included, starts at `libtmux-scala/build/docs/scaladoc/index.html`:

```console
$ ./gradlew :libtmux-scala:scaladocSite
```

Begin with [direct-style `Server`][server] or [Cats `Server`][cats-server].

[server]:
  ../../../libtmux-scala/src/main/scala/io/github/libtmux/scaladsl/Server.scala
[cats-server]:
  ../../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
