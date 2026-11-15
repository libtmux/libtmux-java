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
practical parity. It uses Java types: `Optional` for absence, `AutoCloseable`
servers and typed query fields.

[![CI](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml)
[![libtmux](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux?label=libtmux)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux)
[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/java/)
[![license](https://img.shields.io/badge/license-MIT-blue)](https://github.com/libtmux/libtmux-java/blob/master/LICENSE)

</div>

> Releases are numbered `0.1.N`, and each release increments `N`. The API is not
> settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.

[`Create`][server-newsession] sessions, windows and panes,
[`find and filter`][window_] what a [`Server`][server] holds, and
[`run a command`][pane-run] and read its exit status. It suits tools that drive
a terminal program, test suites that need a real tmux, and agents that share a
terminal.

**Contents** — [Quick start](#quick-start) · [Find
things](#find-sessions-windows-and-panes) · [Create and
run](#create-type-and-run) · [Where am I](#know-where-you-are-running) ·
[Watch](#watch-tmux-change) · [Round trips](#fewer-round-trips) ·
[Modules](#modules) · [Leaves out](#what-it-leaves-out) ·
[Support](#support-and-status) · [Documentation](#documentation)

## Quick start

Add the library through [`libtmux-bom`](libtmux-bom/), which names one version
for every BOM-managed coordinate. It needs JDK 25 or newer:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.1.2"))

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
      <version>0.1.2</version>
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

A [`Server`][server] is a client for one tmux server.
[`Server.start`][server-start] starts a private one that closing the `Server`
stops. This program makes a [`Session`][session], a [`Window`][window] and a
split [`Pane`][pane], runs a command, and leaves nothing behind:

```java
import io.github.libtmux.Pane;
import io.github.libtmux.PaneRun;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import java.time.Duration;

try (Server server = Server.start()) {
    Session session = server.newSession("demo");
    Window window = session.newWindow("build");
    Pane pane = window.split();

    PaneRun ran = pane.run("echo hello from libtmux", Duration.ofSeconds(30));

    ran.output();                      // → [hello from libtmux]
    ran.exitStatus().getAsInt();       // → 0
}
```

Closing a `Server` from [`Server.start`][server-start] stops tmux and removes
its directory. Closing one from [`Server.open`][server], which takes a
[`ServerConfig`][serverconfig] naming a server that already runs, only closes
your client; [`Server.killServer`][server-killserver] stops tmux itself.
[`Session.kill`][session-kill], [`Window.kill`][window-kill] and
[`Pane.kill`][pane-kill] close one thing, and
[`Server.killSession`][server-killsession] closes a session by name.

Later examples start from a `server` you already have, as their `// Given:`
line says:

```java
// Given: Server server
Session session = server.newSession("demo");
Window window = session.newWindow("build");
Pane pane = window.split();

pane.sendLine("echo hello from libtmux");

session.name();                            // → demo
window.refresh().panes().size();           // → 2
```

## Find sessions, windows, and panes

### [`Server.sessions()`][server]: what tmux has now

One read hands you handles. [`Server.windows()`][server] and
[`Server.panes()`][server] are its siblings. Walking the handles issues no
further commands, so a traversal cannot see a half-changed server:

```java
import io.github.libtmux.Window;
import java.util.List;

// Given: Server server
Window editor = server.newSession("demo").newWindow("editor");

List<String> names = server.windows().stream().map(Window::name).sorted().toList();

names.contains("editor");            // → true
editor.session().name();             // → demo
```

A handle holds what tmux said when you read it. [`Window.refresh()`][window]
reads again.

### [`Pane.window`][pane]: walk back up

A handle reaches up as well as down, through [`Pane.window`][pane] and
[`Window.session`][window]:

```java
// Given: Server server
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.window().session().name();      // → libtmux
```

### [`Window_`][window_]: filter without asking tmux again

[`Window_`][window_] has the typed fields of a [`Window`][window]. An expression
built from one is a `Predicate`, so it drops into a stream:

```java
// Given: Server server
server.newSession("build").newWindow("editor");

List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();

editors.size();                      // → 1
editors.get(0).name();               // → editor
```

An expression is a [`FilterExpr`][filterexpr], which is a value, so
[`describe()`][filterexpr] can say what it is — which a lambda cannot:

```java
Window_.name().startsWith("edit").describe();   // → window_name starts-with edit
```

### [`TmuxFilters.format`][tmuxfilters]: what tmux filters, and what stays here

[`Server.windows(FilterExpr)`][server-windows] sends an expression tmux can
evaluate as `list-windows -f`, so tmux returns only the matches. Any other
expression reads the whole server and filters here.
[`TmuxFilters.format`][tmuxfilters] shows which, and is empty when the
expression stays local:

```java
import io.github.libtmux.Pane_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.TmuxFilters;

// Given: Server server
server.newSession("work").newWindow("editor");

FilterExpr<Window> editors = Window_.name().startsWith("edit");

TmuxFilters.format(editors).orElseThrow();      // → #{m:edit*,#{window_name}}
server.windows(editors).size();                 // → 1

FilterExpr<Window> nested = Window_.panes().any(Pane_.command().is("nvim"));

TmuxFilters.format(nested).isPresent();         // → false
```

More: [Filtering](docs/guide/filtering.md#filtering-never-asks-tmux).

### [`Server.session(String)`][server]: one thing, or none

One read answers. [`Server.session(String)`][server] returns an
[`Optional`][optional] that is empty when no session has the name. Call
`orElseThrow` where a missing session is a bug:

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

[`Session.newWindow`][session] and [`Window.split`][window] each return the new
handle:

```java
import io.github.libtmux.Pane;
import io.github.libtmux.Session;
import io.github.libtmux.Window;

// Given: Server server
Session session = server.newSession("demo");
Window editor = session.newWindow("editor");
Pane right = editor.split();

session.name();                      // → demo
editor.name();                       // → editor
editor.refresh().panes().size();     // → 2
```

### [`Pane.sendLine`][pane]: type, then read the screen

[`Pane.sendLine`][pane] types a line into a pane, and [`Pane.capture`][pane]
reads what it shows:

```java
// Given: Server server
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.sendLine("echo hello from libtmux");

pane.capture().isEmpty();            // → false
```

### [`Pane.run`][pane-run]: a command to its end

To get a command's exit status, call [`Pane.run`][pane-run] instead of typing
the command and reading the screen. It returns a [`PaneRun`][panerun] whose
status is the shell's and whose output is the command's alone:

```java
// Given: Pane pane
PaneRun ran = pane.run("printf 'built\\n'; exit 3", Duration.ofSeconds(30));

ran.exitStatus().getAsInt();   // → 3
ran.output();                  // → [built]
```

### [`Pane.resize`][pane-resize]: change a size

[`Pane.resize`][pane-resize] grows a pane by a number of cells toward a
[`Direction`][direction]. [`Pane.resizeTo`][pane-resizeto] and
[`Window.resizeTo`][window-resizeto] set a size in [`Dimensions`][dimensions]:

```java
import io.github.libtmux.Dimensions;
import io.github.libtmux.Direction;
import io.github.libtmux.Pane;

// Given: Window window
window.resizeTo(new Dimensions(100, 30));
Pane top = window.panes().get(0);
Pane bottom = window.split();

top.resizeTo(new Dimensions(100, 20));
bottom.resize(Direction.UP, 3);

top.refresh().size();                // → 100x17
bottom.refresh().size();             // → 100x12
```

### [`Session.options()`][session-options]: set and read an option

[`Options`][options] reaches the options of a [`Session`][session], a
[`Window`][window] or a [`Pane`][pane], and [`Hooks`][hooks] binds a tmux
command to an event at the same scopes:

```java
import io.github.libtmux.OptionKey;

// Given: Session session
session.options().set(OptionKey.HISTORY_LIMIT, 50_000);
session.hooks().set("after-new-window", "display-message created");

session.options().get(OptionKey.HISTORY_LIMIT).orElseThrow();   // → 50000
session.hooks().all().get("after-new-window");   // → [display-message created]
```

More: [Options and hooks](docs/guide/options-and-hooks.md).

### [`Server.cmd`][server]: any tmux command

[`Server.cmd`][server] sends any tmux command. A nonzero exit shows up in the
[`CommandResult`][commandresult] and throws nothing:

```java
// Given: Server server
server.cmd("display-message", "-p", "#{version}").succeeded();   // → true
server.cmd("kill-session", "-t", "=nope").succeeded();           // → false
```

## Know where you are running

### [`TmuxEnvironment`][tmuxenvironment]: which pane am I in

Code running inside a pane can ask where it is. tmux writes `TMUX` and
`TMUX_PANE` into every pane it spawns, and [`TmuxEnvironment`][tmuxenvironment]
reads them back:

```java
import io.github.libtmux.TmuxEnvironment;
import java.util.Map;

// Given: Path socket
Map<String, String> inside = Map.of("TMUX", socket + ",1,$0", "TMUX_PANE", "%0");

TmuxEnvironment here = TmuxEnvironment.of(inside).orElseThrow();

here.session().value();              // → $0
here.pane().orElseThrow().value();   // → %0
```

In a real pane, [`TmuxEnvironment.current()`][tmuxenvironment] reads them for
you and returns empty outside tmux. The example supplies them instead.

A [`ServerConfig`][serverconfig] with no endpoint passes tmux no socket, so tmux
follows `TMUX` itself, unless `LIBTMUX_SOCKET_PATH` or `LIBTMUX_SOCKET` names
another server. [`TmuxEnvironment.config()`][tmuxenvironment] asks for the
pane's server by path.

### [`Server.environment`][environment]: what a later pane will see

tmux keeps an environment per server and per session, and gives it to every
process it starts afterwards. [`Server.environment`][server] reaches the
server's:

```java
// Given: Server server
server.environment().set("LIBTMUX_TOKEN", "abc123");

server.environment().get("LIBTMUX_TOKEN").orElseThrow();   // → abc123
```

## Watch tmux change

### [`Server.control`][server-control]: output as tmux pushes it

[`Server.control`][server-control] attaches a [`ControlClient`][controlclient]
to a session, and [`subscribeOutput`][controlclient] delivers what tmux pushes,
with a gap where the buffer overflowed. It needs tmux 3.7 or newer:

<!-- snippet: control: subscriptions require managed control -->
```java
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.Delivery;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutput;
import java.time.Duration;

// Given: Server server, Session session
try (ControlClient client = server.control(session);
        EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");

    StringBuilder seen = new StringBuilder();
    while (seen.indexOf("streamed") < 0) {
        seen.append(Delivery.kept(output.next(Duration.ofSeconds(5)).orElseThrow()).data());
    }
    seen.indexOf("streamed") >= 0;   // → true
}
```

More: [Streaming](docs/guide/streaming.md).

## Fewer round trips

### [`Server.chain()`][command-chain]: dependent commands in one request

[`Server.batch()`][batch] sends independent commands in one invocation.
[`Server.chain()`][command-chain] does the same for dependent ones, letting tmux
carry the current target from one step to the next, with no round trip to learn
an id:

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

The [process transport][processtransport] and the [control
client][controlclient] log through the JDK's own
[`System.Logger`][system-logger], so the library brings no logging dependency.
Turn on `DEBUG` for `io.github.libtmux` and each command is one line:

```text
tmux list-sessions exited 0 in 4 ms
tmux control display-message complete in 1 ms
```

Only the verb is written, never its arguments: those carry session names, pane
contents and whatever was typed.

## Kotlin and Scala

Both use the Java API directly, because the core is annotated with
[JSpecify](https://jspecify.dev/) and carries no Scala version suffix.

- **Kotlin** reads the JSpecify annotations (since Kotlin 1.5.20), so a value
  is `String` or `String?` and never a platform type. [`Server`][server] is
  `AutoCloseable`, so `use {}` works, and the `Consumer<Builder>` overloads take
  trailing lambdas.
- **[`libtmux-kotlin`](libtmux-kotlin/README.md)** adds what Java cannot
  express: absence as `null` where Java has [`Optional`][optional], and `!expr`
  on a filter.
- **[`libtmux-clojure`](libtmux-clojure/README.md)** is a data-first façade:
  captured maps and vectors for local Clojure processing, Java refs for
  explicit tmux effects, and optional bounded async and control adapters.
- **Scala** can consume the Java artifacts directly; the
  [direct Java guide](docs/guide/scala.md) shows how.
- **[`libtmux-scala`](libtmux-scala/README.md)** is a Scala 3 facade with its
  own `_3` artifacts. The suffix applies only to the facade, never to the Java
  artifact.

## Modules

Group `io.github.libtmux`. Each listed published directory is an artifact with
its own README. Published modules are [on Maven
Central](https://central.sonatype.com/namespace/io.github.libtmux), with Scala
artifacts suffixed `_3`; the Clojure modules below are pre-release.

- **[`libtmux`](libtmux/)** — the library itself. Transport, snapshots,
  entities, options, hooks, batching, control mode, query model.
  **No runtime dependencies**, and a real Java module, `io.github.libtmux`.

- **[`libtmux-bom`](libtmux-bom/)** — name one BOM version, and every
  BOM-managed coordinate below follows it.
[![libtmux-bom](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-bom?label=libtmux-bom)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom)

- **[`libtmux-mcp`](libtmux-mcp/)** — give a model a tmux server over the
  [Model Context Protocol](https://modelcontextprotocol.io/): read panes, run a
  command to its exit status, wait for text.
[![libtmux-mcp](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-mcp?label=libtmux-mcp)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-mcp)

- **[`libtmux-junit5`](libtmux-junit5/)** — test *your* code against real tmux,
  one server per test.
[![libtmux-junit5](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-junit5?label=libtmux-junit5)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-junit5)

- **[`libtmux-kotlin`](libtmux-kotlin/)** — Kotlin ergonomics. Optional: the core
  is already null-safe from Kotlin without it.
[![libtmux-kotlin](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-kotlin?label=libtmux-kotlin)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-kotlin)

- **[`libtmux-clojure`](libtmux-clojure/)** — captured persistent maps and
  vectors, explicit effects, bounded work, and control streams. Pre-release;
  see its README before choosing a coordinate.

- **[`libtmux-clojure-core-async`](libtmux-clojure-core-async/)** — optional
  core.async ownership and backpressure over the Clojure control stream.
  Pre-release with the façade.

- **[`libtmux-scala`](libtmux-scala/)** — Scala 3 collections, opaque handles
  over the Java ones, and the typed query DSL.
[![libtmux-scala_3](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-scala_3?label=libtmux-scala_3)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-scala_3)

- **[`libtmux-scala-cats`](libtmux-scala-cats/)** — optional Cats Effect
  resources and FS2 observations.
[![libtmux-scala-cats_3](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-scala-cats_3?label=libtmux-scala-cats_3)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-scala-cats_3)

- **[`libtmux-scala-ox`](libtmux-scala-ox/)** — an optional Ox `Flow` over
  subscriptions and live views.
[![libtmux-scala-ox_3](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-scala-ox_3?label=libtmux-scala-ox_3)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-scala-ox_3)

- **[`libtmux-jackson`](libtmux-jackson/)** — a filter expression as a versioned
  JSON document, so it can be stored, sent, or written by something that is not
  a Java program.
[![libtmux-jackson](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-jackson?label=libtmux-jackson)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-jackson)

- **[`libtmux-workspace`](libtmux-workspace/)** — build a session from a
  tmuxp-shaped YAML file.
[![libtmux-workspace](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-workspace?label=libtmux-workspace)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace)

- **[`libtmux-workspace-cli`](libtmux-workspace-cli/)** — the `tmux-workspace`
  command: load, capture, convert and import workspaces, with tmuxp's command
  names and JSON or NDJSON output.
[![libtmux-workspace-cli](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-workspace-cli?label=libtmux-workspace-cli)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace-cli)

Not published, and part of how the library is built:
[`examples/`](examples/) · [`integration-tests/`](integration-tests/) ·
[`module-tests/`](module-tests/) · [`benchmarks/`](benchmarks/) ·
[`docs/`](docs/) · [`tools/`](tools/) · `build-logic/`

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
import io.github.libtmux.batch.BatchResult;

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
    implementation(platform("io.github.libtmux:libtmux-bom:0.1.2"))
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
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.FakeTmux;

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
import io.github.libtmux.kotlin.withServer

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

### [`libtmux-clojure`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-clojure): captured Clojure data and explicit effects

The pre-release Clojure façade acquires persistent maps and vectors, preserves
their Java references only for explicit tmux operations, and leaves captured
data free for normal Clojure collection processing. Its
[README](libtmux-clojure/README.md) has an isolated real-tmux quick start and
links its control, core.async, MCP, and measurement boundaries.

### [`libtmux-clojure-core-async`](https://github.com/libtmux/libtmux-java/tree/master/libtmux-clojure-core-async): bounded core.async observation

The optional adapter owns a bounded virtual worker, subscription, events
channel, and terminal channel over a borrowed Clojure control connection. It
does not close the connection that a caller supplies. Its
[README](libtmux-clojure-core-async/README.md) names the ownership and loss
contract.

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
flow starts, then the latest newer view available at each read. Intermediate
views can be coalesced:

<!-- snippet: scala-sync-control: root-ox-live-view -->
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
import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.jackson.FilterJson;
import io.github.libtmux.jackson.LibTmuxModels;
import io.github.libtmux.query.FilterExpr;

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
import io.github.libtmux.Session;
import io.github.libtmux.workspace.Workspace;
import io.github.libtmux.workspace.WorkspaceBuilder;

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

- **Live objects.** A [`Session`][session] or a [`Pane`][pane] is what tmux said
  when it was read, and does not change when tmux does. Read again with
  [`Window.refresh()`][window] to see a change.
- **A logging dependency.** The library logs through the JDK's
  [`System.Logger`][system-logger], and writes only a command's verb, never its
  arguments.
- **Runtime dependencies.** [`libtmux`](libtmux/README.md) has none. The
  adapters carry their own, and [`libtmux-bom`](libtmux-bom/README.md) names
  their versions.
- **Backports.** Only the newest release is supported.

## Support and status

- **JDK 25 or newer**, on the module path or the classpath. `libtmux` is the
  named module `io.github.libtmux`.
- **Linux, including WSL.** macOS is supported on a best-effort basis, with
  limited CI coverage.
- **tmux 3.3a through 3.7c.** The real-tmux suite runs on
  3.3a and 3.7c on every push, and on every release between weekly. tmux 3.8 and
  master are non-blocking lanes. Managed control needs 3.7 or newer and JSON
  layouts need 3.8; the [migration notes](MIGRATION.md#optional-tmux-requirements)
  list the rest. To run the suite against your own tmux builds:

  ```console
  $ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
  ```

- **No release candidates.** A candidate such as `X.Y-rcN` ranks below `X.Y`.
  See [Release candidates][tmux-release-candidates].
- **Any locale.** Text the JVM cannot encode reaches tmux over standard input,
  so names and typed text arrive intact under `LANG=C`
  ([details](MIGRATION.md#non-ascii-text-reaches-tmux-on-any-locale)). Only
  [`Pane.currentPath()`][pane] needs a UTF-8 locale.
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.

Changes that span modules are recorded in [`CHANGELOG.md`](CHANGELOG.md), and
each module keeps a changelog of its own beside its README, linked from it; how
a release is cut is in [`RELEASING.md`](RELEASING.md).

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
| [Kotlin](docs/guide/kotlin.md), [Clojure](docs/guide/clojure.md), and [Scala](docs/guide/scala.md) | The facades |
| [`examples/`](examples/) | Whole programs, each run against a real tmux |
| [`docs/decisions/`](docs/decisions/) | Short ADRs, each citing the tmux behaviour that forced it |
| [`MIGRATION.md`](MIGRATION.md) | What to change when upgrading |
| [`docs/README.md`](docs/README.md) | Every guide, reference and decision, one line each |

## AI policy

Use these packages with AI tools as you like. When contributing, check what you
submit and wait for a maintainer's invitation before preparing a pull request.
See the [AI policy](https://libtmux.org/en/meta/ai-policy/), also kept as
[AI_POLICY.md](https://github.com/libtmux/.github/blob/HEAD/AI_POLICY.md).

## Attribution

Please use the following BibTeX template to cite libtmux-java in scientific
discourse:

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
[dimensions]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-dimensions-dimensions/
[direction]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-direction-direction/
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
[pane-kill]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane-kill/
[pane-resize]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane-resize/
[pane-resizeto]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane-resizeto/
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
[server-control]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-control/
[server-killserver]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-killserver/
[server-killsession]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-killsession/
[server-newsession]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-newsession/
[server-start]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-start/
[server-windows]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-windows/
[serverconfig]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-serverconfig-serverconfig/
[serversnapshot]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-snapshot-serversnapshot-serversnapshot/
[session]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session-session/
[session-kill]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session-session-kill/
[session-options]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session-session-options/
[system-logger]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.Logger.html
[tmux-extension]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-tmuxextension-tmuxextension/
[tmux-release-candidates]: https://libtmux.org/en/tmux/guides/tmux-support/#release-candidates
[tmuxenvironment]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-tmuxenvironment-tmuxenvironment/
[tmuxfilters]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-tmuxfilters-tmuxfilters/
[tmuxtransport]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-transport-tmuxtransport-tmuxtransport/
[window]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window-window/
[window-kill]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window-window-kill/
[window-resizeto]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window-window-resizeto/
[window_]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window_-window_/
[workspace-builder]: https://libtmux.org/en/java/latest/workspace/reference/io-github-libtmux-workspace-workspacebuilder-workspacebuilder-parse/
[workspace-reference]: https://libtmux.org/en/java/latest/workspace/reference/
