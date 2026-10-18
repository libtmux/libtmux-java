<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux for Java

Typed, blocking access to [tmux](https://github.com/tmux/tmux) from the JVM.

A sibling of the Python [libtmux](https://libtmux.git-pull.com/), targeting
practical parity while reading as Java rather than as a translation.

[![CI](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml)
[![tmux matrix](https://github.com/libtmux/libtmux-java/actions/workflows/tmux-matrix.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/tmux-matrix.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux.svg)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#status)

</div>

> **Alpha.** Releases carry an `-alpha` prerelease tag. The API is not
> settled, and any release may change or remove exported identifiers without a
> deprecation period. Pin an exact version. Not recommended for production.

**JDK 25 or newer.** Add the library through
[`libtmux-bom`](libtmux-bom/), which names one version for every BOM-managed
coordinate:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.17"))

    implementation("io.github.libtmux:libtmux")
    testImplementation("io.github.libtmux:libtmux-junit5")
}
```

<details>
<summary>Maven</summary>

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.libtmux</groupId>
      <artifactId>libtmux-bom</artifactId>
      <version>0.0.1-alpha.17</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>io.github.libtmux</groupId>
  <artifactId>libtmux</artifactId>
</dependency>
```

</details>

[Installation](#installation) and [Requirements](#requirements) below cover
the rest: tmux's supported range, locale behaviour, and every module's
coordinate.

```java
// Given: Path socket
ServerConfig config = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(socket))
        .build();

try (Server server = Server.open(config)) {
    Session session = server.newSession("demo");
    Window window = session.newWindow("build");
    Pane pane = window.split();

    pane.sendLine("echo hello from libtmux");
}
```

Which leaves this behind, and reading it back is where the library earns its keep:

```java
// Given: Server server
Session session = server.newSession("demo");
Window window = session.newWindow("build");
Pane pane = window.split();

pane.sendLine("echo hello from libtmux");

session.name();                            // → demo
window.refresh().panes().size();           // → 2
```

**Every Java snippet in this file, in every package README, and in every guide is
compiled, and run against a real tmux** by
[the documentation suite](docs/README.md#how-these-pages-are-tested), unless a
directive above it says `compile-only` or `skip` and why. A snippet that stopped
working fails the build; one that claims the compiler rejects it must actually be
rejected. The Kotlin and Scala fences are run the same way, and every
`libtmux-mcp` and `tmux-workspace` command is held to the flags and variables
those programs read.

A fence's first line, `// Given: Server server` and the like, names what the
snippet *reads* rather than builds — real code still needs its own imports and,
for `Server`, a call such as the `Server.open(ServerConfig...)` shown above.
The suite also hands every snippet a `Server` that already holds one session,
so `server.sessions().get(0)` finds something without the snippet creating it
first; a snippet that opens its own session instead — as several below do —
depends on nothing already being there.

## Quickstart

Each block below runs against a real tmux server, and every value after a `→` is
asserted. If any of them stopped being true, the build would fail.

### Create things

```java
// Given: Server server
Session session = server.newSession("demo");
Window editor = session.newWindow("editor");
Pane right = editor.split();

session.name();                      // → demo
editor.name();                       // → editor
editor.refresh().panes().size();     // → 2
```

### Read the state back

One read hands you handles. Walking them issues no further commands, so a
traversal cannot see a half-changed server.

```java
// Given: Server server
Window editor = server.newSession("demo").newWindow("editor");

List<String> names = server.windows().stream().map(Window::name).sorted().toList();

names.contains("editor");            // → true
editor.session().name();             // → demo
```

### Filter, without asking tmux again

```java
// Given: Server server
server.newSession("build").newWindow("editor");

List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();

editors.size();                      // → 1
editors.get(0).name();               // → editor
```

An expression is a value, so it can also say what it is — which a lambda cannot:

```java
Window_.name().startsWith("edit").describe();   // → window_name starts-with edit
```

### Find one thing

One read answers, and absence is a value rather than an exception, so the caller
says whether it is a bug:

```java
// Given: Server server
server.newSession("build");

server.session("build").orElseThrow().name();   // → build
server.session("absent").isPresent();           // → false
```

Which is how "this session, or a new one" stays a single read:

```java
// Given: Server server
Session work = server.session("work").orElseGet(() -> server.newSession("work"));

work.name();                         // → work
```

### Say how many you expect

```java
// Given: Server server
server.newSession("build");

Session build = Selections.exactlyOne(
        server.sessions().stream().filter(Session_.name().is("build")).toList());

build.name();                        // → build
```

`exactlyOne` raises `CardinalityException.NoMatch` for none and `CardinalityException.MultipleMatches`
for several, because those are different bugs in the calling code.

### Run a command to its end

When the command is yours, run it rather than typing it and reading the screen:
the status is the shell's and the output is only the command's.

```java
// Given: Pane pane
PaneRun ran = pane.run("printf 'built\\n'; exit 3", Duration.ofSeconds(30));

ran.exitStatus().getAsInt();   // → 3
ran.output();                  // → [built]
```

### Send keys and read what a pane shows

```java
// Given: Server server
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.sendLine("echo hello from libtmux");

pane.capture().isEmpty();            // → false
```

### Traverse in both directions

```java
// Given: Server server
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.window().session().name();      // → libtmux
```

### Run any tmux command

Nothing is hidden behind the typed API. Every object can reach tmux directly, and
a nonzero exit is data rather than an exception:

```java
// Given: Server server
server.cmd("display-message", "-p", "#{version}").succeeded();   // → true
server.cmd("kill-session", "-t", "=nope").succeeded();           // → false
```

### Know where you are running

Code running *inside* a pane — a script in a split, a tmux hook, an agent — can
ask where it is. tmux writes `TMUX` and `TMUX_PANE` into every pane it spawns,
and `TmuxEnvironment` reads them back:

```java
// Given: Path socket
Map<String, String> inside = Map.of("TMUX", socket + ",1,$0", "TMUX_PANE", "%0");

TmuxEnvironment here = TmuxEnvironment.of(inside).orElseThrow();

here.session().value();              // → $0
here.pane().orElseThrow().value();   // → %0
```

In a real pane those two variables are already set, so `TmuxEnvironment.current()`
takes nothing and returns empty when there is no pane to describe. This README is
not running inside one, so the example supplies them.

### Change what a later pane will see

tmux keeps an environment per server and per session, and gives it to every
process it starts afterwards — so this is how a long-running session hands a
refreshed value to panes opened from now on.

```java
// Given: Server server
server.environment().set("LIBTMUX_TOKEN", "abc123");

server.environment().get("LIBTMUX_TOKEN").orElseThrow();   // → abc123
```

### See every command tmux ran

The process transport and the control client log through the JDK's own
`System.Logger`, so the library brings no logging dependency and reaches
whatever you already route logging to — `java.util.logging` by default, SLF4J or
Log4j through their bridges. Turn on `DEBUG` for `io.github.libtmux` and each
command is one line: which ran, how it ended, how long it took.

```text
tmux list-sessions exited 0 in 4 ms
tmux control display-message complete in 1 ms
```

Only the verb is written, never its arguments: those carry session names, pane
contents and whatever was typed, a password at a prompt among them.

## Avoid unnecessary round trips

`server.batch()` sends independent commands in one invocation. `server.chain()`
does the same for dependent commands, letting tmux carry the current target from
one step to the next. Both retain an outcome for every operation.

## What it is like to use

**A capture is a moment, not a live view.** `server.sessions()` reads tmux once
and hands back handles. Walking from a session to its windows to their panes and
back issues no further commands, so a traversal cannot observe a half-changed
server. `refresh()` is how you look again.

**Filters are values.** An expression drops into a stream unchanged and can also
be printed, stored, or translated:

```java
// Given: Server server
List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();
```

**A failure says how certain it is.** "tmux never started" and "tmux timed out
halfway" call for opposite recovery, so the transport reports which happened
rather than collapsing both into one error.

## Modules

Group `io.github.libtmux`, on
[Maven Central](https://central.sonatype.com/namespace/io.github.libtmux), the
Scala artifacts suffixed `_3`. Each heading links to the module's directory on
GitHub, where its README lives. Only `libtmux` is required. The other modules
are optional and build on it, and `libtmux-bom` only names their versions.

### [`libtmux`](https://github.com/libtmux/libtmux-java/tree/master/libtmux): the library

Transport, snapshots, entities, options, hooks, batching, control mode, and the
query model, with no runtime dependencies, as the named module
`io.github.libtmux`. Reference: [libtmux.org][java-reference] and
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux). Its
[README](libtmux/README.md#packages) maps the packages.

[`Server.chain`][command-chain] builds a window, splits it, and types into the
new pane in one tmux invocation, with no round trip to learn an id:

```java
// Given: Server server
server.chain()
        .newWindow("built")
        .splitLeftRight()
        .sendLine("echo chained")
        .run();

server.windows().stream().anyMatch(w -> w.name().equals("built"));   // → true
```

### [`libtmux-bom`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-bom): one version for every module

Names one version for every BOM-managed coordinate, so two releases of modules
built against each other cannot be mixed. It has no API of its own; see
[Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom).
Import the platform, and leave the version off the module:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.17"))
    implementation("io.github.libtmux:libtmux-jackson")   // no version
}
```

### [`libtmux-mcp`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-mcp): a tmux server for agents

Gives a model a tmux server over the
[Model Context Protocol](https://github.com/modelcontextprotocol/modelcontextprotocol): it finds its way
around, reads what a pane shows, and runs a command and waits for its exit
status. Reference: [libtmux.org](https://libtmux.org/en/java/latest/mcp/reference/),
the [tool reference](https://libtmux.org/en/java/latest/mcp/tools/), and
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-mcp).

An MCP client starts the launcher itself and speaks to it over stdin and
stdout. Build it from this repository:

```console
$ ./gradlew :libtmux-mcp:installDist
```

With `libtmux-mcp/build/install/libtmux-mcp/bin` on `PATH`, register it with
Claude Code on the tmux socket named `work`, offering only the tools that read
tmux. That is the socket the `tmux-workspace load` command below builds on:

```console
$ claude mcp add tmux \
    -e LIBTMUX_TOOLSETS=inspect \
    -- libtmux-mcp --socket-name work
```

### [`libtmux-junit5`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-junit5): tests against real tmux

Test *your* code against real tmux: `@ExtendWith(TmuxExtension.class)` gives
each test a server of its own and removes it afterwards, and `FakeTmux` stands
in where a test has no tmux at all. Reference:
[libtmux.org](https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-tmuxextension-tmuxextension/)
and [javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-junit5).

[`FakeTmux`][fake-tmux] answers the way tmux answers, so a snapshot of it is a
real snapshot and every handle works:

```java
FakeTmux tmux = new FakeTmux();
PaneId editor = tmux.addSession("work");
tmux.show(editor, "$ make", "make: Nothing to be done.");

try (Server server = tmux.server()) {
    Pane pane = server.pane(editor).orElseThrow();
    pane.capture().get(1);            // → make: Nothing to be done.
}
```

### [`libtmux-kotlin`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-kotlin): Kotlin ergonomics

Coroutine wrappers, a session, window, and split DSL, and `Flow` bridges over
the Java API. Optional: the core is already null-safe from Kotlin without it.
Reference: [libtmux.org](https://libtmux.org/en/kotlin/latest/reference/) and
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-kotlin).

[`withServer`][kotlin-with-server] opens a server and closes it however the
block ends, and [`newSession`][kotlin-session-builder] declares a session, its
windows, and their splits:

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val session = server.newSession {
        name = "editors"
        window { name = "left" }
        window {
            name = "right"
            split { toRight(); percent(30) }
        }
    }

    session.windows.size                  // → 2
}
```

### [`libtmux-scala`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-scala): Scala 3 collections over the handles

Scala collections, opaque handles over the Java ones, and the typed query DSL.
Reference: [libtmux.org](https://libtmux.org/en/scala/latest/reference/) and
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-scala_3).

[`Server.panes`][scala-server] captures once, and the `Pane` fields filter the
captured `Vector` without asking tmux again:

<!-- snippet: scala-sync: root-scala-query-panes -->
```scala
import io.github.libtmux.scaladsl.{config => _, *}
import io.github.libtmux.scaladsl.query.*
import scala.util.Using

Using.resource(Server.open(config)) { server =>
  val panes = server.panes()
  val wide = panes.matching(Pane.width.atLeast(1) && Pane.active.isTrue)
  assert(wide == panes.filter(p => p.info.size().width() >= 1 && p.info.active()))
  assert(wide.exactlyOne.isRight)
}
```

### [`libtmux-scala-cats`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-scala-cats): Cats Effect and FS2

Optional Cats Effect resources and FS2 observations for `libtmux-scala`.
Reference: [libtmux.org](https://libtmux.org/en/scala/latest/reference/) and
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-scala-cats_3).

[`Server.resource`][scala-cats-server] owns the client for the length of `use`,
and the capture it returns is an ordinary vector:

<!-- snippet: scala-io: root-cats-active-panes -->
```scala
import _root_.cats.effect.IO
import io.github.libtmux.scaladsl.cats.{config => _, *}

Server.resource[IO](config).use { server =>
  server.panes().flatMap { panes =>
    val active = panes.filter(_.info.active())
    IO(assert(active.size == 1))
  }
}
```

### [`libtmux-scala-ox`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-scala-ox): Ox flows

An optional Ox `Flow` over subscriptions and live views, for structured
concurrency on virtual threads. Reference:
[libtmux.org](https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-ox-flows/)
and [javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-scala-ox_3).

[`Flows.liveView`][scala-ox-flows] emits the current view of a server when the
flow starts, then every newer one:

<!-- snippet: scala-sync: root-ox-live-view -->
```scala
import _root_.ox.{supervised, useCloseableInScope}
import io.github.libtmux.scaladsl.{config => _, *}
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.ox.Flows

supervised {
  val server = useCloseableInScope(Server.open(config))
  val live = useCloseableInScope(LiveView.attach(server.sessions().head))
  val first = Flows.liveView(live).take(1).runToList()
  assert(first.head.snapshot.sessions().size() == 1)
}
```

### [`libtmux-jackson`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-jackson): filters as JSON

A filter expression as a versioned JSON document, so it can be stored, sent, or
written by something that is not a Java program. Reference:
[libtmux.org](https://libtmux.org/en/java/latest/reference/io-github-libtmux-jackson-filterjson-filterjson/)
and [javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-jackson).

[`FilterJson`][filter-json] writes an expression and reads it back, validated
against a model:

```java
String json = FilterJson.writeString(
        Pane_.command().startsWith("nvim"), LibTmuxModels.pane());

FilterExpr<Pane> restored = FilterJson.readString(json, LibTmuxModels.pane());

restored.describe();                      // → pane_current_command starts-with nvim
```

### [`libtmux-workspace`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-workspace): sessions from YAML

Builds a session from a tmuxp-shaped YAML file. Reference:
[libtmux.org](https://libtmux.org/en/java/latest/workspace/reference/) and
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-workspace).

[`WorkspaceBuilder.parse`][workspace-builder] reads a description into a value
while it is still text, and `build` is what touches tmux:

```java
// Given: Server server
Workspace workspace = WorkspaceBuilder.parse("""
        session_name: built
        windows:
          - window_name: editor
            layout: even-horizontal
            panes:
              - shell_command: echo one
              - shell_command: echo two
        """);

Session session = WorkspaceBuilder.build(server, workspace);

session.windows().get(0).panes().size();          // → 2
```

### [`libtmux-workspace-cli`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-workspace-cli): the `tmux-workspace` command

Loads, captures, converts, and imports workspaces with tmuxp's command names and
JSON or NDJSON output. Reference: the
[command manual](https://libtmux.org/en/java/latest/workspace/cli/) and the
[`load`](https://libtmux.org/en/java/latest/workspace/cli/load/) and
[`freeze`](https://libtmux.org/en/java/latest/workspace/cli/freeze/) pages.

Build the launcher from this repository:

```console
$ ./gradlew :libtmux-workspace-cli:installDist
```

With `libtmux-workspace-cli/build/install/tmux-workspace/bin` on `PATH`, and
[a workspace file](libtmux-workspace-cli/README.md#use-it) saved as
`workspace.yaml`, build it on the tmux socket named `work` without attaching
to it:

```console
$ tmux-workspace load \
    -L work \
    -d \
    ./workspace.yaml
```

Not published, and part of how the library is built:
[`examples/`](examples/) · [`integration-tests/`](integration-tests/) ·
[`module-tests/`](module-tests/) · [`benchmarks/`](benchmarks/) ·
[`docs/`](docs/) · [`tools/`](tools/) · `build-logic/`

A directory is a published artifact exactly when it appears in the list above,
and `platformCoversEveryPublishedModule` fails the build when the published
artifacts stop matching the BOM.

## Installation

Name one BOM version through the platform, and every BOM-managed coordinate
follows it. That is what stops a project mixing two releases of artifacts that
were built against each other.

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.17"))

    implementation("io.github.libtmux:libtmux")
    testImplementation("io.github.libtmux:libtmux-junit5")
}
```

<details>
<summary>Maven</summary>

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.libtmux</groupId>
      <artifactId>libtmux-bom</artifactId>
      <version>0.0.1-alpha.17</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>io.github.libtmux</groupId>
  <artifactId>libtmux</artifactId>
</dependency>
```

</details>

To build against an unreleased change instead, `./gradlew publishToMavenLocal`
installs every module into your local repository under the same coordinates.

## Kotlin and Scala

Both can use the Java API directly, because the core is annotated with
[JSpecify](https://jspecify.dev/) and carries no Scala version suffix.

**Kotlin** sees the API as null-safe rather than as platform types — Kotlin has
read JSpecify since 1.5.20. `Server` is `AutoCloseable`, so `use {}` works, and
the `Consumer<Builder>` overloads take trailing lambdas. `libtmux-kotlin` adds
what Java cannot express: absence as `null` rather than `Optional`, and `!expr`
on a filter.

**Scala** can consume the Java artifacts directly. This source tree also has a
Scala 3 facade with locally staged `_3` artifacts; that suffix applies only to
the facade, never to the Java artifact. See the
[Scala facade guide](libtmux-scala/README.md) and [direct Java guide](docs/guide/scala.md).

## Requirements

JDK 25 or newer, on the module path or the classpath: `libtmux` is the named
module `io.github.libtmux`. The jars carry no OSGi bundle headers.

**Any locale.** A JVM encodes a child process's arguments with the platform's
encoding, which the locale decides before `main` runs, so under `LANG=C` — the
default in most container images — `é` would reach tmux as `?`. When a command
carries text the JVM cannot encode, the library sends it over tmux's standard
input instead, which it writes as UTF-8 itself; names, titles, buffers, options,
environment values and typed text all arrive intact. Reading is unaffected
either way: every command says it reads UTF-8, so what tmux sends back arrives
whole whatever the locale. Only `Pane.currentPath()` still needs a UTF-8 locale,
because a `Path` in this JVM cannot name the directory; `currentPathText()` reads
it anywhere.

tmux 3.2a through 3.7c. That range is not a claim: the whole real-tmux suite runs
against every one of those releases, and each lane checks it really ran the tmux
it is named after.

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

## Documentation

See the [migration notes](MIGRATION.md) when upgrading. Every public type and
member is in the [API reference](https://libtmux.org/en/java/latest/reference/),
built from the current trunk; each released version's Javadoc is on
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux). The
[documentation index](docs/README.md) groups every guide, generated reference,
and decision record, one line each.

- [Getting started](docs/guide/getting-started.md)
- [Filtering](docs/guide/filtering.md)
- [Options and hooks](docs/guide/options-and-hooks.md)
- [Batching and chaining](docs/guide/batching-and-chaining.md)
- [Snapshots and handles](docs/guide/snapshots-and-handles.md)
- [Streaming](docs/guide/streaming.md)
- [Failures, telemetry, and pane input](docs/guide/operating-a-service.md)
- [Threads, cancellation, and what runs at once](docs/guide/concurrency.md)
- [Driving tmux from a model](docs/guide/mcp.md)
- [Testing with real tmux](docs/guide/testing.md)
- [Workspace commands](libtmux-workspace-cli/README.md)
- [Kotlin](docs/guide/kotlin.md) and [Scala](docs/guide/scala.md)
- [Releasing](RELEASING.md)

Whole runnable programs live in [`examples/`](examples/), and the suite there runs
every one of them against a real tmux. The Scala ones cover blocking workspace
operations, bounded concurrent capture, notification loss and reconciliation,
Cats Effect resource ownership and cancellation, and an Ox flow.

Decisions still in force are recorded as short ADRs under
[`docs/decisions/`](docs/decisions/), each citing the tmux behaviour that
forced it.

## Attribution

Please use the following BibTeX template to cite libtmux-java in scientific discourse:

```bibtex
@misc{libtmux-java,
   author = {Tony Narlock},
   year = {2026},
   note = {https://libtmux.org/en/java/},
   title = {libtmux-java: Java wrapper for tmux}
}
```

## Contributing

[`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) covers the gate, the tmux
matrix, and why every server this suite starts lives under a path naming this
port.

## Status

**Alpha.** Releases carry an `-alpha` prerelease tag. The API is not settled,
and any release may change or remove exported identifiers without a deprecation
period. Pin an exact version. Not recommended for production.

What that means in practice:

- **Any release may change or remove exported identifiers**, without a
  deprecation period, including in ways that do not compile.
- **Only the newest release is supported.** There are no backports.
- **Pin an exact version.** A range will move under you.
- What is *not* alpha is the tmux correctness: the whole real-tmux suite runs
  against every supported release on every push.

Changes are recorded in [`CHANGELOG.md`](CHANGELOG.md); how a release is cut is
in [`RELEASING.md`](RELEASING.md).

## License

MIT. See [`LICENSE`](LICENSE).

[command-chain]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-commandchain-commandchain/
[fake-tmux]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-faketmux-faketmux/
[filter-json]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-jackson-filterjson-filterjson/
[java-reference]: https://libtmux.org/en/java/latest/reference/
[kotlin-session-builder]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-server-newsession/
[kotlin-with-server]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-withserver/
[scala-cats-server]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-server/
[scala-ox-flows]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-ox-flows/
[scala-server]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-server/
[workspace-builder]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspacebuilder-workspacebuilder-parse/
