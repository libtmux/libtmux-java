# libtmux for Java

[![CI](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml)
[![tmux matrix](https://github.com/libtmux/libtmux-java/actions/workflows/tmux-matrix.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/tmux-matrix.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux.svg)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#status)

> **Alpha.** Releases carry an `-alpha` prerelease tag. The API is not
> settled, and any release may change or remove exported identifiers without a
> deprecation period. Pin an exact version. Not recommended for production.

Typed, blocking access to [tmux](https://github.com/tmux/tmux) from the JVM.

A sibling of the Python [libtmux](https://libtmux.git-pull.com/), targeting
practical parity while reading as Java rather than as a translation.

**JDK 25 or newer.** Add the library through
[`libtmux-bom`](libtmux-bom/), which names one version for every BOM-managed
coordinate:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.15"))

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
      <version>0.0.1-alpha.15</version>
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
rejected.

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

Group `io.github.libtmux`. Each listed published directory is an artifact with
its own README. They are [on Maven Central](https://central.sonatype.com/namespace/io.github.libtmux);
the Scala artifacts, suffixed `_3`, publish with the rest starting with the first
release that includes them.

- **[`libtmux`](libtmux/)** — the library itself. Transport, snapshots,
  entities, options, hooks, batching, control mode, query model.
  **No runtime dependencies**, and a real Java module, `io.github.libtmux`.

- **[`libtmux-bom`](libtmux-bom/)** — name one BOM version, and every
  BOM-managed coordinate below follows it.

- **[`libtmux-mcp`](libtmux-mcp/)** — give a model a tmux server, over the
  [Model Context Protocol](https://modelcontextprotocol.io/). Finds its way
  around, reads what a pane shows, runs a command and waits for its exit status,
  and pushes notifications as tmux changes.

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

Not published, and part of how the library is built:
[`examples/`](examples/) · [`integration-tests/`](integration-tests/) ·
[`module-tests/`](module-tests/) · [`benchmarks/`](benchmarks/) ·
[`docs/`](docs/) · [`tools/`](tools/) · `build-logic/`

`platformCoversEveryPublishedModule` fails the build when the published artifacts
stop matching the BOM.

The local [`libtmux-workspace-cli`](libtmux-workspace-cli/) application provides the
`tmux-workspace` launcher over native workspace services. It is built as a
distribution and is not a Maven publication.

`libtmux-workspace-cli` and `libtmux-workspace` are two implementations, and the CLI
does not call the library. `libtmux-workspace`'s package documentation states
how their document shapes and building behaviour diverge. Code the CLI's
behaviour against the CLI.

A directory is a published artifact exactly when it appears above, and
`platformCoversEveryPublishedModule` fails the build if that stops being true.

## Installation

Name one BOM version through the platform, and every BOM-managed coordinate
follows it. That is what stops a project mixing two releases of artifacts that
were built against each other.

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.15"))

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
      <version>0.0.1-alpha.15</version>
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
every one of them against a real tmux.

The Scala facade's runnable programs are in
[`libtmux-scala/examples/`](libtmux-scala/examples/): blocking workspace
operations, bounded concurrent capture, notification loss and reconciliation,
and Cats Effect resource ownership and cancellation.

Decisions still in force are recorded as short ADRs under
[`docs/decisions/`](docs/decisions/), each citing the tmux behaviour that
forced it.

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
