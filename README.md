# libtmux for Java

[![CI](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/ci.yml)
[![tmux matrix](https://github.com/libtmux/libtmux-java/actions/workflows/tmux-matrix.yml/badge.svg)](https://github.com/libtmux/libtmux-java/actions/workflows/tmux-matrix.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#status)

> **Alpha.** Every published version carries an `-alpha` qualifier and a `0.0.x`
> number, both of which mean the same thing: the API will change without notice,
> and no release is supported once the next one exists. Pin an exact version.

Typed, blocking access to [tmux](https://github.com/tmux/tmux) from the JVM.

A sibling of the Python [libtmux](https://libtmux.git-pull.com/), targeting
practical parity while reading as Java rather than as a translation.

```java
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

**Every Java snippet in this file, in every package README, and in every guide is
compiled and then run against a real tmux** by [`docs-tests`](docs-tests/). A
snippet that stopped working fails the build; one that claims the compiler rejects
it must actually be rejected.

## Quickstart

Each block below runs against a real tmux server, and every value after a `→` is
asserted. If any of them stopped being true, the build would fail.

### Create things

```java
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
server.newSession("demo").newWindow("editor");

List<String> names = server.windows().stream().map(Window::name).sorted().toList();

names.contains("editor");            // → true
server.sessions().size();            // → 2
```

### Filter, without asking tmux again

```java
server.sessions().get(0).newWindow("editor");

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

### Say how many you expect

```java
server.newSession("build");

Session build = Selections.exactlyOne(
        server.sessions().stream().filter(Session_.name().is("build")).toList());

build.name();                        // → build
```

`exactlyOne` raises `NoMatchException` for none and `MultipleMatchesException`
for several, because those are different bugs in the calling code.

### Send keys and read what a pane shows

```java
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.sendLine("echo hello from libtmux");

pane.capture().isEmpty();            // → false
```

### Traverse in both directions

```java
Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

pane.window().session().name();      // → libtmux
```

### Run any tmux command

Nothing is hidden behind the typed API. Every object can reach tmux directly, and
a nonzero exit is data rather than an exception:

```java
server.cmd("display-message", "-p", "#{version}").succeeded();   // → true
server.cmd("kill-session", "-t", "=nope").succeeded();           // → false
```

### Know where you are running

Code running *inside* a pane — a script in a split, a tmux hook, an agent — can
ask where it is. tmux writes `TMUX` and `TMUX_PANE` into every pane it spawns,
and `TmuxEnvironment` reads them back:

```java
Map<String, String> inside = Map.of("TMUX", socket + ",1,$0", "TMUX_PANE", "%0");

TmuxEnvironment here = TmuxEnvironment.of(inside).orElseThrow();

here.session().value();              // → $0
here.pane().orElseThrow().value();   // → %0
```

In a real pane those two variables are already set, so `TmuxEnvironment.current()`
takes nothing and returns empty when there is no pane to describe. This README is
not running inside one, so the example supplies them.

## Three switches

| to stop                                     | write                          | which costs                    |
| ------------------------------------------- | ------------------------------ | ------------------------------ |
| paying for a process per command            | `.mode(ExecutionMode.CONTROL)` | one tmux client, then reused   |
| waiting on the thread you were handed       | `.mode(ExecutionMode.VIRTUAL)` | a virtual thread per command   |
| round-tripping to learn what you just made  | `server.chain()`               | one request, however many steps |

```java
ServerConfig config = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(socket))
        .mode(ExecutionMode.CONTROL)
        .build();
```

A carrier can also be chosen from outside the program that uses one, so trying
another costs nothing:

```console
$ LIBTMUX_MODE=control java -jar app.jar
```

A carrier and a grouping are separate choices that compose, and neither changes
what a call returns: the same filter answers identically under each. What that
costs is measured in [the benchmark](docs/benchmarks/modes.md), which shows the
identical answers next to the different prices.

## What it is like to use

**A capture is a moment, not a live view.** `server.sessions()` reads tmux once
and hands back handles. Walking from a session to its windows to their panes and
back issues no further commands, so a traversal cannot observe a half-changed
server. `refresh()` is how you look again.

**Filters are values.** An expression drops into a stream unchanged and can also
be printed, stored, or translated:

```java
List<Window> editors = server.windows().stream()
        .filter(Window_.name().startsWith("edit"))
        .toList();
```

**A failure says how certain it is.** "tmux never started" and "tmux timed out
halfway" call for opposite recovery, so the transport reports which happened
rather than collapsing both into one error.

## Modules

Group `io.github.libtmux`. Each directory is its own artifact, with its own
README. Nothing is on Maven Central yet — see [Status](#status).

- **[`libtmux`](libtmux/)** — the library itself. Transport, snapshots,
  entities, options, hooks, batching, control mode, query model.
  **No runtime dependencies.**

- **[`libtmux-bom`](libtmux-bom/)** — name a version once, and every coordinate
  below follows it.

- **[`libtmux-mcp`](libtmux-mcp/)** — give a model a tmux server, over the
  [Model Context Protocol](https://modelcontextprotocol.io/). Lists panes, reads
  what one is showing, runs things in it.

- **[`libtmux-junit5`](libtmux-junit5/)** — test *your* code against real tmux.
  One server per test, guaranteed gone afterwards even if the JVM is killed.

- **[`libtmux-kotlin`](libtmux-kotlin/)** — Kotlin ergonomics. Optional: the core
  is already null-safe from Kotlin without it.

- **[`libtmux-jackson`](libtmux-jackson/)** — a filter expression as a versioned
  JSON document, so it can be stored, sent, or written by something that is not
  a Java program.

- **[`libtmux-workspace`](libtmux-workspace/)** — build a session from a
  tmuxp-shaped YAML file.

Not published, and part of how the library is built:
[`examples/`](examples/) · [`integration-tests/`](integration-tests/) ·
[`docs-tests/`](docs-tests/) · [`benchmarks/`](benchmarks/) ·
[`scripts/`](scripts/) · `build-logic/`

A directory is a published artifact exactly when it appears above, and
`platformCoversEveryPublishedModule` fails the build if that stops being true.

## Installation

Name the version once, through the platform, and every other coordinate follows
it. That is what stops a project mixing two releases of modules that were built
against each other.

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))

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
      <version>0.0.1-alpha.1</version>
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

Nothing is on Maven Central yet. Until the first release, build it locally with
`./gradlew publishToMavenLocal`.

## Kotlin and Scala

Both work without a wrapper, because the core is annotated with
[JSpecify](https://jspecify.dev/) and carries no Scala version suffix.

**Kotlin** sees the API as null-safe rather than as platform types — Kotlin has
read JSpecify since 1.5.20. `Server` is `AutoCloseable`, so `use {}` works, and
the `Consumer<Builder>` overloads take trailing lambdas. `libtmux-kotlin` adds
what Java cannot express: absence as `null` rather than `Optional`, and `!expr`
on a filter.

**Scala** consumes the Java artifacts directly. There is no `_2.13` or `_3`
build, and there should not be — a Java artifact carrying a Scala suffix is a
bug. See [the Scala guide](docs/guide/scala.md).

## Requirements

JDK 21 or newer.

tmux 3.2a through 3.7b. That range is not a claim: the whole real-tmux suite runs
against every one of those releases, and each lane checks it really ran the tmux
it is named after.

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

## Documentation

- [Getting started](docs/guide/getting-started.md)
- [Execution modes](docs/guide/execution-modes.md) — and the [measured comparison](docs/benchmarks/modes.md)
- [Filtering](docs/guide/filtering.md)
- [Options and hooks](docs/guide/options-and-hooks.md)
- [Batching and chaining](docs/guide/batching-and-chaining.md)
- [Snapshots and handles](docs/guide/snapshots-and-handles.md)
- [Streaming](docs/guide/streaming.md)
- [Testing with real tmux](docs/guide/testing.md)
- [Kotlin](docs/guide/kotlin.md) and [Scala](docs/guide/scala.md)
- [Releasing](RELEASING.md)

Whole runnable programs live in [`examples/`](examples/), and the suite there runs
every one of them against a real tmux.

The design is recorded under `docs/spikes/`. Each note carries the measurements
behind the decision it records, including the ones that overturned an earlier
choice.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the gate, the tmux matrix, and why
every server this suite starts lives under a path naming this port.

## Status

**Alpha.** `alpha` is the lowest qualifier Maven's own comparator recognises —
its order runs `alpha < beta < milestone < rc < snapshot < release` — so nothing
published here can sort below what is published today, and every future release
supersedes it cleanly.

What that means in practice:

- **The API will change without notice**, including in ways that do not compile.
- **Only the newest version is supported.** There are no backports.
- **Pin an exact version.** A range will move under you.
- What is *not* alpha is the tmux correctness: the whole real-tmux suite runs
  against all eight supported releases on every push.

Changes are recorded in [`CHANGELOG.md`](CHANGELOG.md); how a release is cut is
in [`RELEASING.md`](RELEASING.md).

## License

MIT. See [`LICENSE`](LICENSE).
