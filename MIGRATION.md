# Migration notes

API changes that require updates to calling code are recorded here. See
[CHANGELOG.md](CHANGELOG.md) for the full change history.

## Next release

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
