# Getting started

The Scala facades need Scala 3.9 and JDK 25 or newer, and tmux 3.2a through
3.7c on the machine they drive.

## Install

Three artifacts, all in group `io.github.libtmux` and all at the same version
as `libtmux` itself. `%%` adds the `_3` suffix that marks a Scala 3 artifact.
None is on Maven Central yet: they publish with the Java artifacts, starting
with the first release that includes them.

- **`libtmux-scala_3`** — direct-style handles, collections and the typed query
  DSL.
- **`libtmux-scala-cats_3`** — Cats Effect resources and FS2 observations.
- **`libtmux-scala-ox_3`** — an Ox `Flow` over subscriptions and live views.

<!-- snippet: scala-build: install-core -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala" % "<version>"
```

For Cats Effect and FS2, or for Ox, add the matching module:

<!-- snippet: scala-build: install-cats -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-cats" % "<version>"
```

<!-- snippet: scala-build: install-ox -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-ox" % "<version>"
```

From Gradle or Maven, name the suffixed artifact directly:
`io.github.libtmux:libtmux-scala_3:<version>`. The core facade depends on
`libtmux` and the Scala 3 library, and on nothing else.

## A first client

The function below takes three caller-supplied values and constructs a Java
`ServerConfig` before opening the Scala client:

| Parameter | Value to supply |
| --- | --- |
| `binary` | Absolute path to your tmux executable |
| `socket` | An isolated socket you own under `/tmp/libtmux-java-dev/` |
| `configFile` | Your tmux configuration file, or `/dev/null` for none |

For example, choose `/tmp/libtmux-java-dev/scala-start/socket`; the function
creates its parent directory. Opening the client does not create tmux;
`newSession` does. The operation creates a session, splits its window, verifies
the resulting panes and kills that session. Closing the client is separate
from session cleanup. An existing server's other sessions remain running;
tmux normally exits when its final session closes.

Call `firstClient` with your three values from your application.
The final call in this tested snippet takes those inputs from the owned test
fixture's `config`; the function builds and uses its own configuration.

<!-- snippet: scala-sync: getting-started-session -->
```scala
import io.github.libtmux.{
  Layout, ServerConfig, ServerEndpoint, SessionSpec, SplitSpec
}
import io.github.libtmux.scaladsl.{config => _, *}
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.util.Using

def firstClient(binary: String, socket: Path, configFile: Path): Unit = {
  require(Path.of(binary).isAbsolute, "supply an absolute tmux executable")
  val ownedSocket = socket.toAbsolutePath.normalize()
  require(
    ownedSocket.startsWith(Path.of("/tmp/libtmux-java-dev")) ||
      ownedSocket.startsWith(Path.of("/tmp/libtmux-java-test")),
    "choose a socket under an owned libtmux Java directory"
  )
  Files.createDirectories(ownedSocket.getParent)
  val selected = ServerConfig.builder()
    .binary(binary)
    .endpoint(ServerEndpoint.socketPath(ownedSocket))
    .configFile(configFile)
    .defaultTimeout(Duration.ofMillis(800))
    .build()

  Using.resource(Server.open(selected)) { server =>
    val session = server.newSession(
      SessionSpec.builder().named("scala-start").running("cat", "-").build()
    )
    try {
      val window = session.windows.head
      val second = window.split(
        SplitSpec.builder().running("cat", "-").build()
      )
      window.selectLayout(Layout.EVEN_HORIZONTAL)
      second.select()
      // window.panes is CAPTURED: it answers from window's own frozen capture, taken before the
      // split, so this refreshes first rather than reading stale data.
      val panes = window.refresh().panes
      assert(panes.size == 2)
      assert(panes.exists(_.info.id().value() == second.info.id().value()))
    } finally session.kill()
  }
}

config.endpoint() match {
  case endpoint: ServerEndpoint.SocketPath =>
    firstClient(
      config.binaryPath(),
      endpoint.path(),
      config.configFile().orElse(Path.of("/dev/null"))
    )
  case _ => throw new IllegalArgumentException("an explicit socket is required")
}
```

Timeouts are `scala.concurrent.duration.FiniteDuration` at every public entry
point, converted once at the boundary: `pane.awaitText("$", 5.seconds)`
never surfaces `java.time.Duration` to the caller.

Follow with [queries](query.md), [ownership](ownership.md), then
[execution](execution.md). For immediate access without the facade, use the
[direct Java guide](../scala.md).

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
