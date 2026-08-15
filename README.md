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

Every example in this file and in the getting-started and filtering guides is
executed by a test — `ExamplesTest` for the core ones, `FilterJsonTest` for the
serialization ones — so a snippet that stopped working fails the build.

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

Group `io.github.libtmux`. Every directory below is its own artifact and its own
README.

| artifact | | what it is for |
| --- | --- | --- |
| **[`libtmux`](libtmux/)** | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux) | the library: transport, snapshots, entities, options, hooks, batching, control mode, query model. **No runtime dependencies.** |
| [`libtmux-bom`](libtmux-bom/) | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-bom?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-bom) | name a version once, for all of the below |
| [`libtmux-mcp`](libtmux-mcp/) | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-mcp?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-mcp) | **give a model a tmux server**, over the Model Context Protocol |
| [`libtmux-junit5`](libtmux-junit5/) | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-junit5?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-junit5) | test *your* code against real tmux, one server per test |
| [`libtmux-kotlin`](libtmux-kotlin/) | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-kotlin?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-kotlin) | Kotlin ergonomics; the core is already null-safe without it |
| [`libtmux-jackson`](libtmux-jackson/) | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-jackson?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-jackson) | a filter expression as a versioned JSON document |
| [`libtmux-workspace`](libtmux-workspace/) | [![](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-workspace?label=)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace) | build a session from a tmuxp-shaped YAML file |

Not published, and part of how the library is built:
[`examples/`](examples/) · [`integration-tests/`](integration-tests/) ·
[`benchmarks/`](benchmarks/) · [`scripts/`](scripts/) · `build-logic/`

A directory is a published artifact exactly when it appears in the table above,
and `platformCoversEveryPublishedModule` fails the build if that stops being true.

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
