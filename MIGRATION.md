# Migration notes

API changes that require updates to calling code are recorded here. See
[CHANGELOG.md](CHANGELOG.md) for the full change history.

## Next release

### Non-ASCII text needs a UTF-8 locale

Commands now refuse text this JVM cannot encode instead of letting tmux receive
`?` in its place. `UnencodableTextException`, a `LibTmuxException` subtype,
names the character and the fix. Set `LC_ALL` or `LANG` to a UTF-8 locale before
the JVM starts; `-Dfile.encoding` and `-Dsun.jnu.encoding` are read too late to
help. ASCII is unaffected on every locale.

Reading needs no change: every command now passes `-u`, so tmux no longer
replaces non-ASCII in a reply with `_` for a client whose locale it cannot
read.

### Four reads raise where they used to answer

`Server.hasSession`, `Options.get`, `Server.listKeys` and `Server.requireAlive`
answered a failed read as though it had found nothing. They now raise, and only
`ServerNotRunningException` means an absent daemon:

| Read | Was | Now |
| --- | --- | --- |
| `hasSession` on an unreachable socket | `false` | `LibTmuxException` |
| `Options.get` on an unreachable socket | `Optional.empty()` | `LibTmuxException` |
| `listKeys` on any failure | `List.of()` | raises |
| `listKeys` with no daemon | started one, listed its tables | `ServerNotRunningException` |
| `requireAlive` on an unreachable socket | `ServerNotRunningException` | `LibTmuxException` |

A missing session is still `false`, and an option tmux does not know is still
empty. Catch `ServerNotRunningException` where you start a daemon on demand,
and `LibTmuxException` for a read that could not be made.

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

### `LegacyFilters` is now `KeywordFilters`

The class parses the `name__contains=dev` form that Python libtmux takes as
keyword arguments, for callers holding such strings in a CLI flag, a config
file or a stored query. Nothing about it is legacy — the name said it was
deprecated here, which it is not. Rename the import and the calls; the API is
otherwise unchanged, including the nested `FieldCatalog`.

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
produces an empty `Optional`. Catch `ServerNotRunningException` to start a
daemon on demand, and `LibTmuxException` for any other failed read; do not
treat either as an empty server. `server.cmd(...)` still returns a completed
nonzero exit as result data, while `server.run(...)` throws.

`Server.hasSession`, `Buffers.show` and `Buffers.delete` follow the same rule:
all three now throw `ServerNotRunningException` for an absent daemon instead
of answering as though nothing matched.

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
