# Getting started

Use JDK 25 or newer and an explicit tmux executable. Run the build commands
from the repository root. `JAVA_HOME` selects the JDK; `TMUX_TEST_BINARY` must
be the absolute path of the tmux binary used by live tests. The checked
`libtmux-scala/sbtw` launcher pins sbt and clears inherited `TMUX` and
`TMUX_PANE` before testing.

## Stage development artifacts

The Scala facade consumes Maven coordinates, including during development.
Stage the Java prerequisite, fixture and optional JSON adapter locally:

```console
$ LIBTMUX_JAVA_VERSION=0.0.1-alpha.14 \
    ./libtmux-scala/scripts/stage-java.sh
```

The default repository is `libtmux-scala/target/java-repository`. The local
stage makes the released Java prerequisite and test fixtures explicit without
using Maven local.

Run the pure and live checks:

```console
$ LIBTMUX_JAVA_VERSION=0.0.1-alpha.14 \
    ./libtmux-scala/sbtw unit live
```

Stage the facade modules:

```console
$ LIBTMUX_JAVA_VERSION=0.0.1-alpha.14 \
    LIBTMUX_SCALA_VERSION=0.0.1-alpha.12-scala-dev.1 \
    ./libtmux-scala/sbtw stage
```

The default Scala repository is `libtmux-scala/target/staging`. Staging writes local
files; it does not upload or publish a release. For another consumer project,
set `LIBTMUX_JAVA_REPOSITORY` and `LIBTMUX_SCALA_STAGING` to those directories'
absolute paths and use the settings below.

<!-- snippet: scala-build: install-staged-core -->
```sbt
resolvers := Seq(
  "Java stage" at file(sys.env("LIBTMUX_JAVA_REPOSITORY")).toURI.toString,
  "Scala stage" at file(sys.env("LIBTMUX_SCALA_STAGING")).toURI.toString,
  "Maven Central" at "https://repo.maven.apache.org/maven2"
)
externalResolvers := resolvers.value
libraryDependencies += "io.github.libtmux" %% "libtmux-scala" %
  "0.0.1-alpha.12-scala-dev.1"
```

For Cats Effect and FS2, add the matching optional adapter:

<!-- snippet: scala-build: install-staged-cats -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-cats" %
  "0.0.1-alpha.12-scala-dev.1"
```

`%%` selects `_3` from the consumer's Scala 3.9 version — this build is Scala
3 only, with no `_2.13` cross-build. The Java library uses the unsuffixed
artifact `libtmux` and a single `%`. A released facade must pin an available
non-SNAPSHOT Java dependency. Do not substitute an unpublished development
coordinate into release installation instructions.

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

Call `firstClient` with your three values in your application or sbt console.
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
[direct Java guide](../../docs/guide/scala.md).

## Build commands

Format Scala source and sbt settings:

```console
$ ./libtmux-scala/sbtw fmt
```

Compile and check formatting:

```console
$ ./libtmux-scala/sbtw lint
```

Generate the facades' API documentation:

```console
$ ./libtmux-scala/sbtw docs
```

The generated Scaladoc starts at `libtmux-scala/target/scala-3.9.0/api/index.html`
and `libtmux-scala-cats/target/scala-3.9.0/api/index.html`. Begin with
[direct-style `Server`][server] or [Cats `Server`][cats-server].

These commands need the Java coordinate selected for the local stage; set
`LIBTMUX_JAVA_VERSION` when it differs from `gradle.properties`. Bootstrap and
compilation belong to the outer verification tier.
Use a resident sbt shell for focused development tests.

[server]:
  ../src/main/scala/io/github/libtmux/scaladsl/Server.scala
[cats-server]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
