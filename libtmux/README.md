# libtmux

**Typed, blocking access to a tmux server. No runtime dependencies.**

This is the whole library. Everything else in the repository is an adapter to
something — Jackson, JUnit, Kotlin, MCP, tmuxp — and depends on this.

`io.github.libtmux:libtmux` — not yet on Maven Central.

> **Alpha.** The API will change without notice. Pin an exact version.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))
    implementation("io.github.libtmux:libtmux")
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

Needs JDK 21 and a tmux between 3.2a and 3.7b.

## Thirty seconds

<!-- snippet: compile-only: opens a second client to the suite's own server, which races it; the behaviour below is what runs -->
```java
ServerConfig config = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(socket))   // wherever you want the server to live
        .build();

try (Server server = Server.open(config)) {
    Session session = server.newSession("demo");
    Window window = session.newWindow("build");
    Pane pane = window.split();

    pane.sendLine("echo hello from libtmux");
    List<String> shown = pane.capture();
}
```

What that leaves, read back:

```java
Session session = server.newSession("demo");
Window window = session.newWindow("build");
Pane pane = window.split();

session.name();                            // → demo
window.name();                             // → build
window.refresh().panes().size();           // → 2
```

Closing a `Server` closes *your client*, not tmux. The session outlives your
program, which is the entire point of tmux.

## What it is like to use

### A capture is a moment, not a live view

`server.sessions()` reads tmux once and hands back handles. Walking from a
session to its windows to their panes and back issues **no further commands**, so
a traversal can never observe a half-changed server.

```java
Session session = server.sessions().get(0);

int panes = session.windows().stream().mapToInt(w -> w.panes().size()).sum();

// Still the one read: walking the capture asked tmux nothing further.
panes;                               // → 1

// This one reads again.
session.refresh().name();            // → libtmux
```

### Filters are values, and they never shell out

An expression is a `Predicate`, so it drops into a stream unchanged — and
because the capture is already in hand, filtering costs nothing.

```java
server.sessions().get(0).newWindow("logs");

List<Window> logs = server.windows().stream()
        .filter(Window_.name().contains("log"))
        .toList();

logs.size();                         // → 1
logs.get(0).name();                  // → logs
```

Typed fields fail at **compile** time, not at runtime:

<!-- snippet: does-not-compile -->
```java
Pane_.index().startsWith("2");   // index is a number
Pane_.active().contains("yes");  // active is a flag
```

When you need exactly one, say so, and get a distinct failure for each way it can
go wrong:

```java
server.newSession("build");

Session build = Selections.exactlyOne(
        server.sessions().stream().filter(Session_.name().is("build")).toList());

build.name();                        // → build
```

`NoMatchException` for none, `MultipleMatchesException` for several — never a
silent `first()`.

Full guide: **[Filtering](../docs/guide/filtering.md)**.

### Three switches when it is too slow

| to stop | write | which costs |
| --- | --- | --- |
| a process per command | `.mode(ExecutionMode.CONTROL)` | one tmux client, then reused |
| blocking the thread you were handed | `.mode(ExecutionMode.VIRTUAL)` | a virtual thread per command |
| round-tripping to learn what you just made | `server.chain()` | one request, however many steps |

```java
ServerConfig fast = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(socket))
        .mode(ExecutionMode.CONTROL)
        .build();
```

Or from outside the program entirely, so trying one costs nothing:

```console
$ LIBTMUX_MODE=control java -jar app.jar
```

**A carrier changes cost, never answers.** The same filter answers identically
under each, and [`ExecutionModeConformanceTest`](../integration-tests/src/test/java/io/github/libtmux/it/ExecutionModeConformanceTest.java)
is where that promise is kept. Measured prices: **[the benchmark](../docs/benchmarks/modes.md)**.

### Several commands, one invocation

```java
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("new-window", "-d", "-n", "two")
        .run();

result.succeeded();                                       // → true
result.operations().size();                               // → 2
result.operations().get(0).outcome();                     // → COMPLETE
```

tmux discards a group after its first failure, so a single exit status cannot say
which command failed or which never ran. Each operation gets its own outcome.

### Options and hooks

```java
Options options = server.sessions().get(0).options();

options.set("status-left", "[libtmux]");

options.get("status-left").orElseThrow();                 // → [libtmux]
```

### Failures say how certain they are

"tmux never started" and "tmux timed out halfway" call for opposite recovery, so
the transport reports which happened rather than collapsing both:

```java
try {
    server.cmd(List.of("kill-session", "-t", "=gone"));
} catch (TmuxTransportException e) {
    if (e.outcome() == DispatchOutcome.NOT_DISPATCHED) {
        retry();      // tmux never saw it, so sending it again is safe
    } else {
        reconcile();  // it may have been applied; look before acting
    }
}
```

## Package map

- **`io.github.libtmux`** — `Server`, `Session`, `Window`, `Pane`, `Client`,
  `Options`, `Hooks`, and the typed fields `Pane_`, `Window_`, `Session_`, `Client_`
- **`io.github.libtmux.query`** — `FilterExpr` and its record nodes, `Selections`, `Fields`
- **`io.github.libtmux.snapshot`** — the immutable capture a traversal reads from
- **`io.github.libtmux.transport`** — how a command travels: `TmuxTransport`,
  `CommandResult`, and dispatch certainty
- **`io.github.libtmux.control`** — `ControlClient`, for control mode and `%output`
- **`io.github.libtmux.batch`** — `Batch`, `BatchResult`, per-operation outcomes
- **`io.github.libtmux.format`** — tmux format templates and row parsing

## Next

- [Getting started](../docs/guide/getting-started.md) · [Snapshots and handles](../docs/guide/snapshots-and-handles.md)
- [Filtering](../docs/guide/filtering.md) · [Batching and chaining](../docs/guide/batching-and-chaining.md)
- [Execution modes](../docs/guide/execution-modes.md) · [Streaming](../docs/guide/streaming.md)
- [Options and hooks](../docs/guide/options-and-hooks.md)
- Runnable programs: [`examples/`](../examples/)
- Testing your own code against real tmux: [`libtmux-junit5`](../libtmux-junit5/)
