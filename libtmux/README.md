<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux

**Typed, blocking access to a tmux server. No runtime dependencies.**

</div>

This is the whole library. Everything else in the repository is an adapter to
something — Jackson, JUnit, Kotlin, MCP, tmuxp — and depends on this.

[Open a server][server], [create][server-newsession] sessions, windows and
panes, [filter][window_] what it holds, [run a command][pane-run] to its exit
status, and [hear changes][controlclient] as tmux makes them.

`io.github.libtmux:libtmux` — [on Maven Central][central].
API reference: [libtmux.org][reference] for the current trunk,
[javadoc.io][javadoc-libtmux] for a released version.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.

**Contents** — [Quick start](#quick-start) · [Read](#read-what-tmux-has) ·
[Filter](#filter-and-select) · [Batch](#send-several-commands) ·
[Options and hooks](#read-and-set-options-and-hooks) ·
[Failures](#know-how-far-a-command-got) · [Packages](#package-map) ·
[Support](#support-and-status)

## Quick start

Add it through the BOM. It needs JDK 25 and a tmux between 3.3a and 3.7c:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.1.0"))
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
      <version>0.1.0</version>
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

[`Server.open`][server] connects to the tmux server a
[`ServerConfig`][serverconfig] describes, and
[`Server.newSession(String)`][server-newsession] creates a [`Session`][session].
A session makes [`Window`][window]s, and a window splits into [`Pane`][pane]s:

```java
// Given: Path socket
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
// Given: Server server
Session session = server.newSession("demo");
Window window = session.newWindow("build");
Pane pane = window.split();

session.name();                            // → demo
window.name();                             // → build
window.refresh().panes().size();           // → 2
```

Closing a [`Server`][server] closes your client. The tmux server and its
sessions keep running after your program exits.

## Read what tmux has

### [`server.sessions()`][server]: one read, then handles

[`server.sessions()`][server] reads tmux once and hands back handles. Walking
from a session to its windows to their panes and back issues **no further
commands**, so a traversal can never observe a half-changed server.

```java
// Given: Server server
Session session = server.sessions().get(0);

int panes = session.windows().stream().mapToInt(w -> w.panes().size()).sum();

// Still the one read: walking the capture asked tmux nothing further.
panes;                               // → 1

// This one reads again.
session.refresh().name();            // → libtmux
```

## Filter and select

### [`Window_`][window_]: filters are values, and they never shell out

[`Window_`][window_] has the typed fields of a [`Window`][window]. An
expression is a [`FilterExpr`][filterexpr] and a `Predicate`, so it drops into a
stream unchanged — and because the capture is already in hand, filtering costs
nothing.

```java
// Given: Server server
server.sessions().get(0).newWindow("logs");

List<Window> logs = server.windows().stream()
        .filter(Window_.name().contains("log"))
        .toList();

logs.size();                         // → 1
logs.get(0).name();                  // → logs
```

Typed fields ([`Pane_`][pane_], made with [`Fields`][fields]) fail at
**compile** time, not at runtime:

<!-- snippet: does-not-compile: method startsWith -->
```java
Pane_.index().startsWith("2");   // index is a number
Pane_.active().contains("yes");  // active is a flag
```

### [`Selections.exactlyOne`][selections]: say how many you expect

When you need exactly one, say so, and get a distinct failure for each way it
can go wrong:

```java
// Given: Server server
server.newSession("build");

Session build = Selections.exactlyOne(
        server.sessions().stream().filter(Session_.name().is("build")).toList());

build.name();                        // → build
```

[`CardinalityException.NoMatch`][cardinality] for none,
`CardinalityException.MultipleMatches` for several — never a silent `first()`.

Full guide: **[Filtering](../docs/guide/filtering.md)**.

## Send several commands

### [`server.batch()`][batch]: independent commands in one invocation

[`server.batch()`][batch] collects independent commands and runs them in one
invocation:

```java
// Given: Server server
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("new-window", "-d", "-n", "two")
        .run();

result.succeeded();                                       // → true
result.operations().size();                               // → 2
result.operations().get(0).outcome();                     // → COMPLETE
```

tmux discards a group after its first failure, so a single exit status cannot
say which command failed or which never ran. Each operation gets its own
outcome.

## Read and set options and hooks

### [`Options`][options]: tmux options at each scope

[`Options`][options] reads and sets tmux options, and [`Hooks`][hooks] registers
commands for tmux events; the [options and hooks topic][options-and-hooks]
covers the scopes:

```java
// Given: Server server
Options options = server.sessions().get(0).options();

options.set("status-left", "[libtmux]");

options.get("status-left").orElseThrow();                 // → [libtmux]
```

## Know how far a command got

### [`DispatchException`][dispatchexception]: failures say how certain they are

"tmux never started" and "tmux timed out halfway" call for opposite recovery, so
the [transport][tmuxtransport] reports which of the two happened, and
[`DispatchException`][dispatchexception] says how far a command got:

```java
// Given: Server server
try {
    server.cmd(List.of("kill-session", "-t", "=gone"));
} catch (DispatchException e) {
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
- **`io.github.libtmux.control`** — `ControlClient`, for subscriptions and `%output`
- **`io.github.libtmux.batch`** — `Batch`, `BatchResult`, per-operation outcomes
- **`io.github.libtmux.format`** — tmux format templates and row parsing
- **`io.github.libtmux.exception`** — `LibTmuxException`, the sealed tree of every
  failure the library raises
- **`io.github.libtmux.catalog`** — `Operation` and `Kind`, which classify each public
  method for the facades

## Packages

Each heading below links to its directory on GitHub. The module exports the nine
packages above and no others; `io.github.libtmux.internal` is public only
because they share it.

Kotlin, Scala, Jackson, JUnit 5, MCP and the workspace tools are modules of
their own, under [Modules][modules] in the root README.

### [`io.github.libtmux`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux): servers, sessions, windows, and panes

[`Server`][server], [`Session`][session], [`Window`][window], [`Pane`][pane],
[`Client`][client], [`Options`][options], and [`Hooks`][hooks], with the typed
fields [`Pane_`][pane_], [`Window_`][window_], [`Session_`][session_], and
[`Client_`][client_]. Reference:
[javadoc.io][javadoc-libtmux-2]
and [libtmux.org][libtmux-org-en-java-latest-reference].

[`Pane.run`][pane-run] runs a command in a pane and waits for it. A nonzero
exit status comes back in the result and throws nothing:

```java
// Given: Pane pane
PaneRun ran = pane.run("printf 'built\\n'; exit 3", Duration.ofSeconds(30));

ran.exitStatus().getAsInt();   // → 3
ran.output();                  // → [built]
```

### [`io.github.libtmux.query`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/query): filters that run and print

[`FilterExpr`][filterexpr] is a `Predicate` and also a value that prints itself,
and [`Selections`][selections] says how many matches a caller expects.
Reference: [javadoc.io][javadoc-query] and [libtmux.org][filterexpr].

```java
// Given: Server server
server.newSession("build").newWindow("editor");

FilterExpr<Window> editors =
        Window_.name().startsWith("edit").and(Window_.name().endsWith("or"));

editors.describe();                  // → (window_name starts-with edit and window_name ends-with or)
server.windows(editors).size();      // → 1
```

### [`io.github.libtmux.snapshot`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/snapshot): a hierarchy as it was

[`ServerSnapshot`][serversnapshot] answers from what it captured, so walking it
asks tmux nothing. [`ServerMirror`][servermirror] keeps one current. Reference:
[javadoc.io][javadoc-snapshot]
and [libtmux.org][serversnapshot].

```java
// Given: Server server
ServerSnapshot snapshot = server.snapshot();
SessionState first = snapshot.sessions().get(0);

first.name();                                  // → libtmux
snapshot.windowsOf(first.id()).size();         // → 1
```

### [`io.github.libtmux.transport`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/transport): one command, one result

[`TmuxTransport`][tmuxtransport] runs a tmux command, and
[`CommandResult`][commandresult] carries what came back. A nonzero exit is data.
Reference:
[javadoc.io][javadoc-transport]
and [libtmux.org][commandresult].

```java
// Given: Server server
CommandResult listed = server.cmd("list-sessions", "-F", "#{session_name}");

listed.stdout();                     // → [libtmux]
listed.succeeded();                  // → true
```

### [`io.github.libtmux.control`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/control): staying attached, and being told

[`ControlClient`][controlclient] holds a control-mode connection, and
[`EventSubscription`][eventsubscription] delivers what tmux pushes, with a gap
where the buffer overflowed. Reference:
[javadoc.io][javadoc-control]
and [libtmux.org][controlclient].

<!-- snippet: control: subscriptions require managed control -->
```java
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

### [`io.github.libtmux.batch`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/batch): several commands, an outcome for each

[`Batch`][batch] sends independent commands in one invocation, and
[`BatchResult`][batchresult] reports every operation for itself. Reference:
[javadoc.io][javadoc-batch]
and [libtmux.org][batch].

```java
// Given: Server server
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("display-message", "-p", "#{window_name}")
        .run();

result.succeeded();                         // → true
result.operations().get(0).outcome();       // → COMPLETE
result.operations().get(1).outcome();       // → COMPLETE
```

### [`io.github.libtmux.format`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/format): listing templates and their rows

[`RowFormat`][rowformat] builds the template for a listing and reads the rows
back. Its separator is generated per process, so a window named with a
separator cannot shift the fields after it. Reference:
[javadoc.io][javadoc-format]
and [libtmux.org][rowformat].

```java
// Given: Server server
RowFormat format = RowFormat.of("session_name", "session_windows");

CommandResult listed = server.cmd("list-sessions", "-F", format.template());
RowFormat.Row row = format.rows(listed.stdout()).get(0);

row.text("session_name");            // → libtmux
row.number("session_windows");       // → 1
```

### [`io.github.libtmux.exception`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/exception): one sealed tree of failures

[`LibTmuxException`][libtmuxexception] is sealed, so a `switch` over it fails to
compile when a new kind of failure appears. Reference:
[javadoc.io][javadoc-exception]
and [libtmux.org][libtmuxexception].

```java
Function<LibTmuxException, String> nextStep = failure -> switch (failure) {
    case TargetGoneException gone -> "look it up again";
    case ServerUnavailableException unavailable -> "start a server";
    case CommandRejectedException rejected -> "change the request";
    case DispatchException dispatch -> dispatch.safeToRetry() ? "send it again" : "read state first";
    case ControlEndedException ended -> "attach again";
    case UnsupportedFeatureException unsupported -> "do without";
    case UnencodableTextException unencodable -> "use a UTF-8 locale";
    case MalformedResponseException malformed -> "report it";
    case CardinalityException.NoMatch none -> "nothing matched";
    case CardinalityException.MultipleMatches many -> "ambiguous";
};

nextStep.apply(new CardinalityException.MultipleMatches("many", 3));   // → ambiguous
```

### [`io.github.libtmux.catalog`](https://github.com/libtmux/libtmux-java/tree/master/libtmux/src/main/java/io/github/libtmux/catalog): what kind of operation each method is

[`Operation`][operation] gives a public method a [`Kind`][kind] — captured,
read, mutation, wait, stream, or lifecycle — which is how the Kotlin and Scala
facades and the generated reference are built from one list. Reference:
[javadoc.io][javadoc-catalog]
and [libtmux.org][operation].

```java
var rename = Session.class.getMethod("rename", String.class);

rename.getAnnotation(io.github.libtmux.catalog.Operation.class).value();   // → MUTATION
```

## What it leaves out

- **Runtime dependencies.** The library has none; the adapters depend on it.
- **Live objects.** A handle reads from one capture, and
  [`refresh()`][window] looks again.
- **A settled API.** Any release may change or remove exported identifiers.
  Pin an exact version.

## Support and status

- **JDK 25 or newer**, and **tmux 3.3a through 3.7c**.
- **Linux, including WSL.** CI runs on Ubuntu. macOS is supported on a
  best-effort basis, and CI coverage for it is highly limited.
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.
- **Reference.** [libtmux.org][reference] for the current trunk,
  [javadoc.io][javadoc-libtmux] for a released version.

## Documentation

- [Getting started](../docs/guide/getting-started.md) · [Snapshots and
  handles](../docs/guide/snapshots-and-handles.md)
- [Filtering](../docs/guide/filtering.md) · [Batching and
  chaining](../docs/guide/batching-and-chaining.md)
- [Streaming](../docs/guide/streaming.md) ·
  [Concurrency](../docs/guide/concurrency.md)
- [Options and hooks](../docs/guide/options-and-hooks.md)
- Runnable programs: [`examples/`](../examples/)
- Testing your own code against real tmux:
  [`libtmux-junit5`](../libtmux-junit5/)
- The [root README](../README.md), with every module

[batch]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-batch-batch-batch/
[batchresult]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-batch-batchresult-batchresult/
[cardinality]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-cardinalityexception-cardinalityexception/
[central]: https://central.sonatype.com/artifact/io.github.libtmux/libtmux
[client]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-client-client/
[client_]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-client_-client_/
[commandresult]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-transport-commandresult-commandresult/
[controlclient]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-controlclient-controlclient/
[dispatchexception]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-dispatchexception-dispatchexception/
[eventsubscription]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-eventsubscription-eventsubscription/
[fields]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-fields-fields/
[filterexpr]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-filterexpr-filterexpr/
[hooks]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-hooks-hooks/
[javadoc-batch]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/batch/package-summary.html
[javadoc-catalog]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/catalog/package-summary.html
[javadoc-control]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/control/package-summary.html
[javadoc-exception]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/exception/package-summary.html
[javadoc-format]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/format/package-summary.html
[javadoc-libtmux]: https://javadoc.io/doc/io.github.libtmux/libtmux
[javadoc-libtmux-2]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/package-summary.html
[javadoc-query]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/query/package-summary.html
[javadoc-snapshot]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/snapshot/package-summary.html
[javadoc-transport]: https://javadoc.io/doc/io.github.libtmux/libtmux/latest/io.github.libtmux/io/github/libtmux/transport/package-summary.html
[kind]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-catalog-kind-kind/
[libtmux-org-en-java-latest-reference]: https://libtmux.org/en/java/latest/reference/
[libtmuxexception]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-libtmuxexception-libtmuxexception/
[modules]: ../README.md#modules
[operation]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-catalog-operation-operation/
[options]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-options-options/
[options-and-hooks]: https://libtmux.org/en/java/latest/topics/options-and-hooks/
[pane]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane/
[pane-run]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane-pane-run/
[pane_]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-pane_-pane_/
[reference]: https://libtmux.org/en/java/latest/reference/
[rowformat]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-format-rowformat-rowformat/
[selections]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-query-selections-selections/
[server]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server/
[server-newsession]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server-newsession/
[serverconfig]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-serverconfig-serverconfig/
[servermirror]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-snapshot-servermirror-servermirror/
[serversnapshot]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-snapshot-serversnapshot-serversnapshot/
[session]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session-session/
[session_]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-session_-session_/
[tmuxtransport]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-transport-tmuxtransport-tmuxtransport/
[window]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window-window/
[window_]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-window_-window_/
