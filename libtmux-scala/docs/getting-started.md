# Getting started

Use JDK 21 or 25 and an explicit tmux executable. Run the build commands from
the repository root. `JAVA_HOME` selects the JDK; `TMUX_TEST_BINARY` must be the
absolute path of the tmux binary used by live tests. The checked `libtmux-scala/sbtw`
launcher pins sbt and clears inherited `TMUX` and `TMUX_PANE` before testing.

## Stage development artifacts

The Scala facade consumes Maven coordinates, including during development.
Stage the Java prerequisite, fixture and optional JSON adapter locally:

```console
$ LIBTMUX_JAVA_VERSION=0.0.1-alpha.12-scala-dev.1 \
    ./libtmux-scala/scripts/stage-java.sh
```

The default repository is `libtmux-scala/target/java-repository`. The development
coordinate includes the inherited-option fix required by this wrapper; it is
not evidence that a public Java release contains that change.

Run both producer families' pure and live checks:

```console
$ LIBTMUX_JAVA_VERSION=0.0.1-alpha.12-scala-dev.1 \
    ./libtmux-scala/sbtw crossUnit crossLive
```

Stage the two facade modules for both Scala binary families:

```console
$ LIBTMUX_JAVA_VERSION=0.0.1-alpha.12-scala-dev.1 \
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

`%%` selects `_2.13` or `_3` from the consumer's Scala version. The Java library
uses the unsuffixed artifact `libtmux` and a single `%`. A released facade must
pin an available non-SNAPSHOT Java dependency. Do not substitute an unpublished
development coordinate into release installation instructions.

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
import io.github.libtmux.scaladsl.blocking.Server
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
      val captured = window.refresh()
      assert(captured.panes.size == 2)
      assert(captured.panes.exists(_.info.id == second.info.id))
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

Follow with [queries](query.md), [ownership](ownership.md), then
[execution](execution.md). For immediate access without the facade, use the
[direct Java guide](../../docs/guide/scala.md).

## Build commands

Format Scala source and sbt settings:

```console
$ ./libtmux-scala/sbtw fmt
```

Compile both producer families and check formatting:

```console
$ ./libtmux-scala/sbtw lint
```

Generate both families' API documentation:

```console
$ ./libtmux-scala/sbtw docs
```

The generated Scaladoc starts at `libtmux-scala/target/scala-2.13/api/index.html`
and `libtmux-scala-cats/target/scala-2.13/api/index.html`. Scala 3 uses the
corresponding `scala-3.3.8/api/` directories. Begin with
[blocking `Server`][blocking-server] or [Cats `Server`][cats-server].

These commands need the same Java development coordinate when the staged
version differs from `gradle.properties`; set `LIBTMUX_JAVA_VERSION` for the
invocation. Bootstrap and compilation belong to the outer verification tier.
Use a resident sbt shell for focused development tests.

[blocking-server]:
  ../src/main/scala/io/github/libtmux/scaladsl/blocking/Server.scala
[cats-server]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
