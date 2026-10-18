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
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#support-and-status)

</div>

> **Alpha.** Releases carry an `-alpha` prerelease tag. The API is not
> settled, and any release may change or remove exported identifiers without a
> deprecation period. Pin an exact version. Not recommended for production.

[`Create`][server-newsession] sessions, windows and panes,
[`find and filter`][window_] what a [`Server`][server] holds, and [`run a command`][pane-run]
and read its exit status. It suits tools that drive a terminal program, test
suites that need a real tmux, and agents that share a terminal.

**Contents** — [Quick start](#quick-start) · [Find things](#find-sessions-windows-and-panes) ·
[Create and run](#create-type-and-run) · [Where am I](#know-where-you-are-running) ·
[Round trips](#fewer-round-trips) · [Modules](#modules) ·
[Leaves out](#what-it-leaves-out) · [Support](#support-and-status) ·
[Documentation](#documentation)

## Quick start

Add the library through [`libtmux-bom`](libtmux-bom/), which names one version
for every BOM-managed coordinate. It needs JDK 25 or newer:

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

A [`Server`][server] is a client for one tmux server. [`Server.open`][server] takes a
[`ServerConfig`][serverconfig] that describes the server to reach, and
[`Server.newSession(String)`][server-newsession] creates a [`Session`][session]. A session holds
[`Window`][window]s, and a window splits into [`Pane`][pane]s:

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

That leaves a session behind on a tmux server of its own. Reading it back is
where the library earns its keep:

```java
// Given: Server server
Session session = server.newSession("demo");
Window window = session.newWindow("build");
Pane pane = window.split();

pane.sendLine("echo hello from libtmux");

session.name();                            // → demo
window.refresh().panes().size();           // → 2
```

Every value after a `→` is asserted against a real tmux: the
[documentation suite](docs/README.md#how-these-pages-are-tested) runs each Java
block on this page. A block's first line, `// Given: Server server`, names what
it reads rather than builds.

## Find sessions, windows, and panes

### [`Server.sessions()`][server]: what tmux has now

One read hands you handles. [`Server.windows()`][server] and [`Server.panes()`][server] are its
siblings. Walking the handles issues no further commands, so a traversal cannot
see a half-changed server:

```java
// Given: Server server
Window editor = server.newSession("demo").newWindow("editor");

List<String> names = server.windows().stream().map(Window::name).sorted().toList();

names.contains("editor");            // → true
editor.session().name();             // → demo
```

A capture is a moment, not a live view. [`Window.refresh()`][window] is how you look
again.

### [`Pane.window`][pane]: walk back up

A handle reaches up as well as down, through [`Pane.window`][pane] and
[`Window.session`][window]:

```java
// Given: Server server
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.window().session().name();      // → libtmux
```

### [`Window_`][window_]: filter without asking tmux again

[`Window_`][window_] has the typed fields of a [`Window`][window]. An expression built from one
is a `Predicate`, so it drops into a stream:

```java
// Given: Server server
server.newSession("build").newWindow("editor");

List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();

editors.size();                      // → 1
editors.get(0).name();               // → editor
```

An expression is a [`FilterExpr`][filterexpr], which is a value, so [`describe()`][filterexpr] can say
what it is — which a lambda cannot:

```java
Window_.name().startsWith("edit").describe();   // → window_name starts-with edit
```

### [`Server.session(String)`][server]: one thing, or none

One read answers. [`Server.session(String)`][server] returns an [`Optional`][optional], so absence
is a value rather than an exception, and the caller says whether it is a bug
with `orElseThrow`:

```java
// Given: Server server
server.newSession("build");

server.session("build").orElseThrow().name();   // → build
server.session("absent").isPresent();           // → false
```

That is how "this session, or a new one" stays a single read:

```java
// Given: Server server
Session work = server.session("work").orElseGet(() -> server.newSession("work"));

work.name();                         // → work
```

### [`Selections.exactlyOne`][selections]: say how many you expect

[`Selections.exactlyOne`][selections] takes the matches and insists on one:

```java
// Given: Server server
server.newSession("build");

Session build = Selections.exactlyOne(
        server.sessions().stream().filter(Session_.name().is("build")).toList());

build.name();                        // → build
```

It raises [`CardinalityException.NoMatch`][cardinality] for none and
`CardinalityException.MultipleMatches` for several, because those are different
bugs in the calling code.

## Create, type, and run

### [`Session.newWindow`][session]: make things

[`Session.newWindow`][session] and [`Window.split`][window] each return the new handle:

```java
// Given: Server server
Session session = server.newSession("demo");
Window editor = session.newWindow("editor");
Pane right = editor.split();

session.name();                      // → demo
editor.name();                       // → editor
editor.refresh().panes().size();     // → 2
```

### [`Pane.sendLine`][pane]: type, then read the screen

[`Pane.sendLine`][pane] types a line into a pane, and [`Pane.capture`][pane] reads what it
shows:

```java
// Given: Server server
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.sendLine("echo hello from libtmux");

pane.capture().isEmpty();            // → false
```

### [`Pane.run`][pane-run]: a command to its end

When the command is yours, run it rather than typing it and reading the screen.
[`Pane.run`][pane-run] returns a [`PaneRun`][panerun] whose status is the shell's and whose output
is only the command's:

```java
// Given: Pane pane
PaneRun ran = pane.run("printf 'built\\n'; exit 3", Duration.ofSeconds(30));

ran.exitStatus().getAsInt();   // → 3
ran.output();                  // → [built]
```

### [`Server.cmd`][server]: any tmux command

Nothing is hidden behind the typed API. [`Server.cmd`][server] sends any tmux command,
and a nonzero exit is data in the [`CommandResult`][commandresult], not an exception:

```java
// Given: Server server
server.cmd("display-message", "-p", "#{version}").succeeded();   // → true
server.cmd("kill-session", "-t", "=nope").succeeded();           // → false
```

## Know where you are running

### [`TmuxEnvironment`][tmuxenvironment]: which pane am I in

Code running inside a pane can ask where it is. tmux writes `TMUX` and
`TMUX_PANE` into every pane it spawns, and [`TmuxEnvironment`][tmuxenvironment] reads them back:

```java
// Given: Path socket
Map<String, String> inside = Map.of("TMUX", socket + ",1,$0", "TMUX_PANE", "%0");

TmuxEnvironment here = TmuxEnvironment.of(inside).orElseThrow();

here.session().value();              // → $0
here.pane().orElseThrow().value();   // → %0
```

In a real pane those variables are already set, so
[`TmuxEnvironment.current()`][tmuxenvironment] takes nothing and returns empty when there is no
pane to describe. This README is not running inside one, so the example supplies
them.

### [`Server.environment`][environment]: what a later pane will see

tmux keeps an environment per server and per session, and gives it to every
process it starts afterwards. [`Server.environment`][server] reaches the server's:

```java
// Given: Server server
server.environment().set("LIBTMUX_TOKEN", "abc123");

server.environment().get("LIBTMUX_TOKEN").orElseThrow();   // → abc123
```

## Fewer round trips

### [`Server.chain()`][command-chain]: dependent commands in one request

[`Server.batch()`][batch] sends independent commands in one invocation.
[`Server.chain()`][command-chain] does the same for dependent ones, letting tmux carry the
current target from one step to the next, with no round trip to learn an id:

```java
// Given: Server server
server.chain()
        .newWindow("built")
        .splitLeftRight()
        .sendLine("echo chained")
        .run();

server.windows().stream().anyMatch(w -> w.name().equals("built"));   // → true
```

Both retain an outcome for every operation. A failure says how certain it is:
"tmux never started" and "tmux timed out halfway" call for opposite recovery, so
the [transport][tmuxtransport] reports which happened.

### See every command tmux ran

The [process transport][processtransport] and the
[control client][controlclient] log through the JDK's own
[`System.Logger`][system-logger], so the library brings no logging dependency. Turn on `DEBUG`
for `io.github.libtmux` and each command is one line:

```text
tmux list-sessions exited 0 in 4 ms
tmux control display-message complete in 1 ms
```

Only the verb is written, never its arguments: those carry session names, pane
contents and whatever was typed.

## Kotlin and Scala

Both use the Java API directly, because the core is annotated with
[JSpecify](https://jspecify.dev/) and carries no Scala version suffix.

- **Kotlin** sees the API as null-safe rather than as platform types, since
  Kotlin has read JSpecify since 1.5.20. [`Server`][server] is `AutoCloseable`, so
  `use {}` works, and the `Consumer<Builder>` overloads take trailing lambdas.
- **[`libtmux-kotlin`](libtmux-kotlin/README.md)** adds what Java cannot
  express: absence as `null` rather than [`Optional`][optional], and `!expr` on a filter.
- **Scala** can consume the Java artifacts directly; the
  [direct Java guide](docs/guide/scala.md) shows how.
- **[`libtmux-scala`](libtmux-scala/README.md)** is a Scala 3 facade with its
  own `_3` artifacts. The suffix applies only to the facade, never to the Java
  artifact.

## Modules

Group `io.github.libtmux`. Each listed published directory is an artifact with
its own README. They are [on Maven Central](https://central.sonatype.com/namespace/io.github.libtmux),
the Scala artifacts suffixed `_3`.

- **[`libtmux`](libtmux/)** — the library itself. Transport, snapshots,
  entities, options, hooks, batching, control mode, query model.
  **No runtime dependencies**, and a real Java module, `io.github.libtmux`.

- **[`libtmux-bom`](libtmux-bom/)** — name one BOM version, and every
  BOM-managed coordinate below follows it.

- **[`libtmux-mcp`](libtmux-mcp/)** — give a model a tmux server, over the
  [Model Context Protocol](https://modelcontextprotocol.io/). Finds its way
  around, reads what a pane shows, runs a command and waits for its exit status,
  and has bounded waits for text, a channel or new output in place of pushed
  notifications.

- **[`libtmux-junit5`](libtmux-junit5/)** — test *your* code against real tmux.
  One server per test. A JVM killed outright leaves its server for the next
  run sharing the fixture root to reap.

- **[`libtmux-kotlin`](libtmux-kotlin/)** — Kotlin ergonomics. Optional: the core
  is already null-safe from Kotlin without it.

- **[`libtmux-scala`](libtmux-scala/)** — Scala 3 collections, opaque handles
  over the Java ones, and the typed query DSL.

- **[`libtmux-scala-cats`](libtmux-scala-cats/)** — optional Cats Effect
  resources and FS2 observations.

- **[`libtmux-scala-ox`](libtmux-scala-ox/)** — an optional Ox `Flow` over
  subscriptions and live views.

- **[`libtmux-jackson`](libtmux-jackson/)** — a filter expression as a versioned
  JSON document, so it can be stored, sent, or written by something that is not
  a Java program.

- **[`libtmux-workspace`](libtmux-workspace/)** — build a session from a
  tmuxp-shaped YAML file.

- **[`libtmux-workspace-cli`](libtmux-workspace-cli/)** — the `tmux-workspace`
  command: load, capture, convert and import workspaces, with tmuxp's command
  names and JSON or NDJSON output. A separate implementation from
  `libtmux-workspace`, whose package documentation states how their document
  shapes and building behaviour diverge.

Not published, and part of how the library is built:
[`examples/`](examples/) · [`integration-tests/`](integration-tests/) ·
[`module-tests/`](module-tests/) · [`benchmarks/`](benchmarks/) ·
[`docs/`](docs/) · [`tools/`](tools/) · `build-logic/`

A directory is a published artifact exactly when it appears in the list above,
and `platformCoversEveryPublishedModule` fails the build when the published
artifacts stop matching the BOM.

Each heading below links to the module's directory on GitHub, where its README
lives. Only `libtmux` is required. The other modules are optional and build on
it, and `libtmux-bom` only names their versions.

### [`libtmux`](https://github.com/libtmux/libtmux-java/tree/master/libtmux): the library

[Transport][tmuxtransport], [snapshots][serversnapshot], entities,
[options][options], [hooks][hooks], [batching][batch], [control
mode][controlclient], and the [query model][queries], with no runtime
dependencies, as the named module `io.github.libtmux`.

Reference: [libtmux.org][java-reference] and [javadoc.io][javadoc-libtmux]. Its
[README](libtmux/README.md#packages) maps the packages.

[`Server.batch`][batch] sends independent commands in one invocation, and
[`BatchResult`][batchresult] reports an outcome for each:

```java
// Given: Server server
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("new-window", "-d", "-n", "two")
        .run();

result.succeeded();                         // → true
result.operations().size();                 // → 2
result.operations().get(0).outcome();       // → COMPLETE
```

### [`libtmux-bom`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-bom): one version for every module

Names one version for every BOM-managed coordinate, so two releases of modules
built against each other cannot be mixed. It has no API of its own; see
[Maven Central][central-bom].

Import the platform, and leave the version off the module:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.17"))
    implementation("io.github.libtmux:libtmux-jackson")   // no version
}
```

### [`libtmux-mcp`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-mcp): a tmux server for agents

Gives a model a tmux server over the [Model Context Protocol][mcp-spec]. It
finds its way around, reads what a pane shows, and runs a command and waits for
its exit status.

Reference: [libtmux.org][mcp-reference], the [tool pages][mcp-tools], and
[javadoc.io][javadoc-mcp].

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

Test *your* code against real tmux. `@ExtendWith(TmuxExtension.class)` gives
each test a server of its own and removes it afterwards, and `FakeTmux` stands
in where a test has no tmux at all.

Reference: [`TmuxExtension`][tmux-extension] on libtmux.org and
[javadoc.io][javadoc-junit5].

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

Reference: [libtmux.org][kotlin-reference] and [javadoc.io][javadoc-kotlin].

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

Reference: [libtmux.org][scala-reference] and [javadoc.io][javadoc-scala].

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

Reference: [libtmux.org][scala-reference] and [javadoc.io][javadoc-scala-cats].

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
concurrency on virtual threads.

Reference: [`Flows`][scala-ox-flows] on libtmux.org and
[javadoc.io][javadoc-scala-ox].

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
written by something that is not a Java program.

Reference: [`FilterJson`][filter-json] on libtmux.org and
[javadoc.io][javadoc-jackson].

[`FilterJson`][filter-json] writes an expression and reads it back, validated
against a model:

```java
String json = FilterJson.writeString(
        Pane_.command().startsWith("nvim"), LibTmuxModels.pane());

FilterExpr<Pane> restored = FilterJson.readString(json, LibTmuxModels.pane());

restored.describe();                      // → pane_current_command starts-with nvim
```

### [`libtmux-workspace`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-workspace): sessions from YAML

Builds a session from a tmuxp-shaped YAML file.

Reference: [libtmux.org][workspace-reference] and
[javadoc.io][javadoc-workspace].

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
JSON or NDJSON output.

It is a separate implementation from `libtmux-workspace`, whose package
documentation states how their document shapes and building behaviour diverge.

Reference: the [command manual][cli-manual], with pages for [`load`][cli-load]
and [`freeze`][cli-freeze].

Build the launcher from this repository:

```console
$ ./gradlew :libtmux-workspace-cli:installDist
```

With `libtmux-workspace-cli/build/install/tmux-workspace/bin` on `PATH`, and
[a workspace file](libtmux-workspace-cli/README.md#quick-start) saved as
`workspace.yaml`, build it on the tmux socket named `work` without attaching
to it:

```console
$ tmux-workspace load \
    -L work \
    -d \
    ./workspace.yaml
```

## What it leaves out

- **Live objects.** A [`Session`][session] or a [`Pane`][pane] is what tmux said when it was
  read, and does not change when tmux does. Read again with
  [`Window.refresh()`][window] to see a change.
- **A logging dependency.** The library logs through the JDK's
  [`System.Logger`][system-logger], and writes only a command's verb, never its arguments.
- **Runtime dependencies.** [`libtmux`](libtmux/README.md) has none. The
  adapters carry their own, and [`libtmux-bom`](libtmux-bom/README.md) names
  their versions.
- **Backports.** Only the newest release is supported.

## Support and status

- **JDK 25 or newer**, on the module path or the classpath: `libtmux` is the
  named module `io.github.libtmux`, and the jars carry no OSGi bundle headers.
- **tmux 3.2a through 3.7c.** The whole real-tmux suite runs against every one
  of those releases, and each lane checks it ran the tmux it is named after:

  ```console
  $ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
  ```

- **Any locale.** Text the JVM cannot encode reaches tmux over standard input,
  so names and typed text arrive intact under `LANG=C`
  ([details](MIGRATION.md#non-ascii-text-reaches-tmux-on-any-locale)). Only
  [`Pane.currentPath()`][pane] needs a UTF-8 locale.
- **Alpha.** Any release may change or remove exported identifiers without a
  deprecation period, including in ways that do not compile. Pin an exact
  version. Not recommended for production.
- **tmux correctness is not alpha.** The real-tmux suite runs against every
  supported release on every push.

Changes are recorded in [`CHANGELOG.md`](CHANGELOG.md); how a release is cut is
in [`RELEASING.md`](RELEASING.md).

## Documentation

Every public type and member is in the
[API reference](https://libtmux.org/en/java/latest/reference/), built from the
current trunk; each released version's Javadoc is on
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux).

| | |
| --- | --- |
| [Getting started](docs/guide/getting-started.md) | The first program, compiled and run |
| [Filtering](docs/guide/filtering.md) | An expression is a value |
| [Options and hooks](docs/guide/options-and-hooks.md) | Reading and setting both |
| [Batching and chaining](docs/guide/batching-and-chaining.md) | Several commands, one invocation |
| [Snapshots and handles](docs/guide/snapshots-and-handles.md) | What a capture freezes |
| [Streaming](docs/guide/streaming.md) | Watching pane output as it happens |
| [Failures, telemetry, and pane input](docs/guide/operating-a-service.md) | Driving tmux from a service |
| [Threads and cancellation](docs/guide/concurrency.md) | What runs at once |
| [Driving tmux from a model](docs/guide/mcp.md) | The `libtmux-mcp` server |
| [Testing with real tmux](docs/guide/testing.md) | `libtmux-junit5` |
| [`tmux-workspace`](docs/guide/workspace-cli.md) | The rules `load`, `convert` and `import` follow |
| [Kotlin](docs/guide/kotlin.md) and [Scala](docs/guide/scala.md) | The facades |
| [`examples/`](examples/) | Whole programs, each run against a real tmux |
| [`docs/decisions/`](docs/decisions/) | Short ADRs, each citing the tmux behaviour that forced it |
| [`MIGRATION.md`](MIGRATION.md) | What to change when upgrading |
| [`docs/README.md`](docs/README.md) | Every guide, reference and decision, one line each |

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

## License

MIT. See [`LICENSE`](LICENSE).

[batch]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-batch-batch-batch/
[batchresult]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-batch-batchresult-batchresult/
[cardinality]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-cardinalityexception-cardinalityexception/
[central-bom]: https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom
[cli-freeze]: https://libtmux.org/en/java/latest/workspace/cli/freeze/
[cli-load]: https://libtmux.org/en/java/latest/workspace/cli/load/
[cli-manual]: https://libtmux.org/en/java/latest/workspace/cli/
[command-chain]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-commandchain-commandchain/
[commandresult]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-transport-commandresult-commandresult/
[controlclient]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-controlclient-controlclient/
[environment]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-environment-environment/
[fake-tmux]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-faketmux-faketmux/
[filter-json]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-jackson-filterjson-filterjson/
[filterexpr]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-filterexpr-filterexpr/
[hooks]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-hooks-hooks/
[java-reference]: https://libtmux.org/en/java/latest/reference/
[javadoc-jackson]: https://javadoc.io/doc/io.github.libtmux/libtmux-jackson
[javadoc-junit5]: https://javadoc.io/doc/io.github.libtmux/libtmux-junit5
[javadoc-kotlin]: https://javadoc.io/doc/io.github.libtmux/libtmux-kotlin
[javadoc-libtmux]: https://javadoc.io/doc/io.github.libtmux/libtmux
[javadoc-mcp]: https://javadoc.io/doc/io.github.libtmux/libtmux-mcp
[javadoc-scala]: https://javadoc.io/doc/io.github.libtmux/libtmux-scala_3
[javadoc-scala-cats]: https://javadoc.io/doc/io.github.libtmux/libtmux-scala-cats_3
[javadoc-scala-ox]: https://javadoc.io/doc/io.github.libtmux/libtmux-scala-ox_3
[javadoc-workspace]: https://javadoc.io/doc/io.github.libtmux/libtmux-workspace
[kotlin-reference]: https://libtmux.org/en/kotlin/latest/reference/
[kotlin-session-builder]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-server-newsession/
[kotlin-with-server]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-withserver/
[mcp-reference]: https://libtmux.org/en/java/latest/mcp/reference/
[mcp-spec]: https://github.com/modelcontextprotocol/modelcontextprotocol
[mcp-tools]: https://libtmux.org/en/java/latest/mcp/tools/
[optional]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html
[options]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-options-options/
[pane]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane/
[pane-run]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane-run/
[panerun]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-panerun-panerun/
[processtransport]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-transport-processtransport-processtransport/
[queries]: https://libtmux.org/en/java/latest/concepts/queries/
[scala-cats-server]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-cats-server/
[scala-ox-flows]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-ox-flows/
[scala-reference]: https://libtmux.org/en/scala/latest/reference/
[scala-server]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-server/
[selections]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-selections-selections/
[server]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server/
[server-newsession]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-newsession/
[serverconfig]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-serverconfig-serverconfig/
[serversnapshot]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-snapshot-serversnapshot-serversnapshot/
[session]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session-session/
[system-logger]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.Logger.html
[tmux-extension]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-tmuxextension-tmuxextension/
[tmuxenvironment]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-tmuxenvironment-tmuxenvironment/
[tmuxtransport]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-transport-tmuxtransport-tmuxtransport/
[window]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window-window/
[window_]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window_-window_/
[workspace-builder]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspacebuilder-workspacebuilder-parse/
[workspace-reference]: https://libtmux.org/en/java/latest/workspace/reference/
