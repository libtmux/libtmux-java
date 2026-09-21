# Migration notes

API changes that require updates to calling code are recorded here. See
[CHANGELOG.md](CHANGELOG.md) for the full change history.

## Next release

A breaking type is named on its own `api-break:` line. Mentioning the type in
the prose is not that line.

### `EventSubscription.next` returns a gap before the events that remain

api-break: EventSubscription

`next` and `next(Duration)` return `Optional<Delivery<T>>`. A full buffer still
drops its oldest event. The next read is `Delivery.Gap`, carrying how many were
discarded since the previous read, and the reads after that are the events that
remain. `droppedCount()` is still the total.

`cause()` is empty when the caller closed the subscription. It is set when the
control client ended it. `ControlClient.standardError()` is the bounded text
that process wrote to its error stream. A subscription does not reconnect.
Attach again with `Server.control` and read a snapshot. Events already missed
are not replayed.

### Prompt history is `server.prompt()`

api-break: Server

`promptHistory` and `clearPromptHistory` are `prompt().history()` and
`prompt().clear()`. `messages()` is `messageLog().lines()`. `runShell`,
`runShellCapturing` and `ifShell` are `shell().run`, `shell().capturing` and
`shell().choose`.

### A tmux release candidate keeps its name and counts as its release

`TmuxVersion` now carries the pre-release a version named, so a server running
`3.8-rc` reads back and prints as `3.8-rc` rather than `3.8`. Two consequences
for calling code.

Comparison treats a candidate as the release it names: `atLeast` against `3.8`
is met by `3.8-rc`, because tmux freezes features at the candidate and a 3.8
candidate behaves as 3.8 does. A candidate still sorts above the `next-3.8`
development build tracking toward it, still below `3.8a`, and is still not
`equals` to `3.8` — so code branching on `equals` sees a value it did not see
before, while code branching on `atLeast` gains the candidate.

The record gained a fifth component, `preRelease`. The four-argument
constructor still exists and builds a version with none, so existing calls
compile and behave as before; only `equals`, `hashCode` and `toString` widen to
take the new component into account.

### Non-ASCII text reaches tmux on any locale

A command carrying text this JVM cannot encode as an argument — any non-ASCII
text under `LANG=C`, the default in most container images — is sent over tmux's
standard input instead, with `source-file -`, and arrives intact. No change is
needed. `UnencodableTextException`, a `LibTmuxException` subtype, remains for
the one case with no second route: a command that already reads standard input,
or an endpoint — the binary or socket path — this JVM cannot encode, since
nothing but an argument can carry those. It names the character and the fix,
`LC_ALL=C.UTF-8`, and never the text itself.

Reading needs no change: every command now passes `-u`, so tmux no longer
replaces non-ASCII in a reply with `_` for a client whose locale it cannot
read.

### `ServerSnapshot.of` needs the server's identity

The public overload taking a capture time and a pid but no `TmuxVersion` is
gone, and the overload taking no identity at all is package-private. A handle
built from either failed at its first real operation, because every handle
command is fenced on the server's pid and version, so neither belonged in the
public API. Build one with the overload that takes both.

```java
// Given: Server server
io.github.libtmux.snapshot.ServerSnapshot.of(
        java.time.Instant.now(),
        server.snapshot().serverPid().orElseThrow(),
        server.version(),
        java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
```

### Key bindings are a view: `server.keys()`

`bindKey`, `unbindKey` and `listKeys` moved to `Keys`, like buffers, options,
hooks and the environment, and gained key tables: `in("root")` names one.
Binding without one uses `prefix`, as before; listing without one lists every
table, as before.

| Was | Is |
| --- | --- |
| `server.bindKey(key, command)` | `server.keys().bind(key, command)` |
| `server.unbindKey(key)` | `server.keys().unbind(key)` |
| `server.listKeys()` | `server.keys().list()` |

```java
// Given: Server server
server.keys().bind("F12", java.util.List.of("display-message", "hello"));
server.keys().unbind("F12");
```

### `Window.layout()` answers a `WindowLayout`

It was a `String` holding tmux's classic checksummed form before 3.8 and JSON
from 3.8, and nothing but a comment said which. `WindowLayout` is sealed over
`Classic` and `Json`, and `value()` is the text exactly as tmux reported it.
`applyLayout` takes either the `WindowLayout` or, as before, a `String`.
`Notification.LayoutChanged` carries one too.

```java
// Given: Window window
String saved = window.layout().value();
window.applyLayout(window.layout());
```

### `ControlEvent` names panes and windows with their own types

`ControlEvent.paneId()` answers `Optional<PaneId>` and `windowId()`
`Optional<WindowId>`, where both answered `Optional<String>`; compare with a
handle's `id()` rather than its `id().value()`. The record gained a fourth
component, `notification()`, its typed reading; the three-argument constructor
remains and derives it.

<!-- snippet: compile-only: an event needs a control client and a change to report; ControlWatchIntegrationTest runs the comparison against real tmux -->
```java
// Given: Window window, ControlEvent event
event.windowId().filter(window.id()::equals).isPresent();
```

### A cancelled channel wait throws `InterruptedException`

`Channel.await`, `Channel.awaitReservingCapacity` and `Channel.drain` now declare
`InterruptedException`, as `Pane.awaitText` already did. An interrupted channel
wait used to raise an unchecked `TmuxTransportException` with the interrupt flag
left set, so cancelling one kind of wait and cancelling the other had to be
handled two different ways. Add `throws InterruptedException` at the call site,
or catch it and restore the flag.

```java
// Given: Server server
try {
    server.channel("ready").await(java.time.Duration.ofSeconds(30));
} catch (InterruptedException cancelled) {
    Thread.currentThread().interrupt();
}
```

### `Pane.awaitText` answers with `TextOutcome`, not `WakeReason`

The return type changed. `WakeReason.SIGNALLED` became two answers, because a
screen wait can end a way a channel never does:

| Was | Is |
| --- | --- |
| `SIGNALLED` | `TextOutcome.APPEARED` when the wait saw the text arrive |
| `SIGNALLED` | `TextOutcome.PRESENT_AT_ENTRY` when the first look already showed it |
| `TIMED_OUT` | `TextOutcome.TIMED_OUT` |
| `SERVER_GONE` | `TextOutcome.SERVER_GONE` |

`PRESENT_AT_ENTRY` says only that the text was there on the first look. It may
be output from a command that finished before the wait read, or it may have been
on the pane for an hour; a screen cannot tell those apart. What it replaces is
worse: both used to be reported as though the wait had watched the text arrive,
so a marker left by an earlier run satisfied the next wait for it in
milliseconds. Treat both as "the text is there" unless the difference matters,
and when it does, append `; tmux wait-for -S name` to your own command and block
on `Server.channel`, which is exact.

`Pane.await(Predicate, Duration)` still answers with `WakeReason`: you wrote that
condition and can test it before waiting, so the library is not the only thing
that can see it was already true.

### `Pane.awaitText` ignores an echo of what this library typed

A wait no longer matches the pane's echo of text sent through `send`, `sendLine`,
`sendLiteral` or `paste`, and now finds text the terminal wrapped across rows.
A caller that waited for a marker its own command line contained was being
answered by the echo; it now waits for the command to produce it. Waiting for
text identical to what was just typed cannot be distinguished from the echo and
will time out — send a marker the command prints, or signal a
`Server.channel`, which is exact.

The echo is taken out where it stands as a whole word, rather than by dropping
every row that mentions it: a command's own output routinely names the command,
and `make` answering `make: *** No targets specified ... Stop.` used to have its
answer dropped along with its question. `id` now comes off `$ id` and stays
inside `uid=1000`. The cost is that output repeating the typed text as a word of
its own loses that word — `make: ***` reads `: ***` to a wait — while its answer,
`Stop.`, is still there. `Pane.send(String)` records an echo too, since tmux
types anything there that is not one of its key names.

`TypedText` is that record, and it is public because the wait is not the only
thing that needs it: take it once with `TypedText.in(pane)` and reuse it for
every look, since what a pane is holding ages out and a watcher that asked again
each time would stop discounting the echo partway through. `Pane.noteTyped`
tells it about keys that reached a pane some other way, which is what
`synchronize-panes` does.

`Pane.capture()` is unaffected and still answers with rows as the pane displays
them.

<!-- snippet: compile-only: needs a shell that has finished starting, which a shared fixture pane cannot promise; the behaviour itself is gated against every supported tmux by PaneWaitFidelityIntegrationTest -->
```java
// Given: Pane pane
pane.sendLine("./build --target release");

// Answered by what the build prints, not by the echo of the line that started it.
pane.awaitText("release", java.time.Duration.ofSeconds(60));
```

### `PaneState.currentPath` is text

A directory name is bytes to tmux, and converting one this JVM cannot represent
throws — which, in a capture, lost every other pane over one pane's directory.
`PaneState`'s `currentPath` component is now a `String`; a direct constructor
call passes the text rather than a `Path`.

`Pane.currentPath()` still answers with a `Path`, converting on request, and
throws `LibTmuxException` only when this JVM cannot represent that name.
`Pane.currentPathText()` is the value tmux reported and never throws.

```java
// Given: Pane pane
pane.currentPathText().isEmpty();      // → false
```

### The interactive chooser methods on `Pane` are removed

`clockMode`, `chooseTree`, `customizeMode`, `chooseBuffer`, `chooseClient`, the
three `findWindow` overloads and `FindSpec` are gone. They open something for a
person at an attached client, and a program cannot observe what happens next. To
open one anyway, send tmux's own command; `Pane.mode()` still reports the mode
and `exitMode()` still leaves it. To find a window, filter `Server.windows()`.

```java
// Given: Pane pane
pane.server().cmd("choose-tree", "-t", pane.id().value());
```

### `LegacyFilters` is removed

It parsed the `name__contains=dev` form Python libtmux takes as keyword
arguments, which is Python's calling convention rather than anything a Java
caller writes. Build a `FilterExpr` from the typed fields in code; for a filter
that arrives as text — a CLI flag, a config file, a stored query — read it with
`FilterJson.readString` from `libtmux-jackson`, which checks it against a model
and fails closed on any name the model did not declare.

### Exception and guard names

Replace the old imports, catch types and calls, then recompile:

| Previous name | Current name |
| --- | --- |
| `ObjectDoesNotExist` | `ObjectDoesNotExistException` |
| `UnsupportedTmuxVersion` | `UnsupportedTmuxVersionException` |
| `server.raiseIfDead()` | `server.requireAlive()` |

Both exceptions still extend `LibTmuxException`. `requireAlive()` now throws
`ServerNotRunningException`, a `LibTmuxException` subtype, when no daemon
answers; it still preserves transport failures. The old names have no
forwarding aliases.

```java
// Given: Server server
server.requireAlive();
server.isAlive();                      // → true
```

### Failed live reads

Live listings, finders and snapshot capture throw when a read fails. An
absent daemon throws `ServerNotRunningException`; any other failed capture
throws `LibTmuxException`. A missing object in a successful capture still
produces an empty `Optional`.

These reads also distinguish absence from a failed read:

| Read | Was | Now |
| --- | --- | --- |
| `Server.hasSession` on an unreachable socket | `false` | `LibTmuxException` |
| `Options.get` on an unreachable socket | `Optional.empty()` | `LibTmuxException` |
| `Server.listKeys` (now `Keys.list`) on any failure | `List.of()` | raises |
| `listKeys` with no daemon | started one, listed its tables | `ServerNotRunningException` |
| `Server.requireAlive` on an unreachable socket | `ServerNotRunningException` | `LibTmuxException` |

A missing session is still `false`, and an option tmux does not know is still
empty. `Buffers.show` and `Buffers.delete` also throw
`ServerNotRunningException` for an absent daemon instead of answering as
though nothing matched.

Catch `ServerNotRunningException` to start a daemon on demand, and
`LibTmuxException` for any other failed read; do not treat either as an empty
server. `server.cmd(...)` still returns a completed nonzero exit as result
data, while `server.run(...)` throws.

A finder requires a running daemon. `server.newSession("build")` can start
one; [`BuildAWorkspace`][workspace-example] shows how to handle both an
existing session and an endpoint with no daemon.

[workspace-example]: examples/src/main/java/io/github/libtmux/examples/BuildAWorkspace.java

### Discarded replacement handles

Error Prone flags discarded results from `Session.rename`, `Window.rename`,
`Pane.retitle` and entity `refresh` methods through `@CheckReturnValue`. Retain
the returned handle to read the updated capture; the original remains
unchanged.

```java
// Given: Session session
Session renamed = session.rename("build");
renamed.name();                        // → build
```

### `Pane.pid()` returns `OptionalLong`

`0` meant two different things depending on release — no process at all, and,
on a development tmux, a process that ran and died — and could be mistaken
for a real pid either way. Replace `pane.pid() > 0` with `pane.pid()
.orElseThrow() > 0`, and a bare `long pid = pane.pid();` with `orElse(0)` if a
sentinel is still wanted, or `orElseThrow()` where absence should fail loudly.

```java
// Given: Pane pane
pane.pid().isEmpty();                  // → false
```

`PaneState`'s own `pid` component changed the same way, from `long` to
`OptionalLong`; a caller constructing one directly wraps the value in
`OptionalLong.of(...)` or passes `OptionalLong.empty()`. `PaneState` also
gained a `position` component (a `PanePosition`) between `size` and `title` —
a direct constructor call needs one more argument, in that position.

### `ControlClient.attach` requests JSON layouts on connect

`attach` now sends `refresh-client -f new-layouts` right after attaching, so
a `%layout-change` notification agrees with what a plain client reads on tmux
3.8+ instead of carrying the classic string. A fake control-mode server built
for a test — one that scripts an exact sequence of requests and replies
rather than answering every request generically — now has one more request
to answer, right after the attach reply, before whatever its own script
expects next.
