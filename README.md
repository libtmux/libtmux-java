# libtmux for Java

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

| artifact            | contents                                                              |
| ------------------- | --------------------------------------------------------------------- |
| `libtmux`           | transport, snapshots, entities, options, hooks, batching, control mode, query model. No runtime dependencies. |
| `libtmux-jackson`   | the versioned JSON form of a filter expression                        |
| `libtmux-junit5`    | a JUnit 5 extension giving each test its own tmux server              |
| `libtmux-workspace` | builds a session from a tmuxp-shaped YAML description                 |
| `libtmux-mcp`       | exposes a tmux server to a model over the Model Context Protocol      |

Group `com.git-pull`, artifact `libtmux`. Not yet published to Maven Central;
build locally with:

```console
$ ./gradlew publishToMavenLocal
```

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

The design is recorded under `docs/spikes/`. Each note carries the measurements
behind the decision it records, including the ones that overturned an earlier
choice.

## Status

Under construction. The API is not yet stable.
