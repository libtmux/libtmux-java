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

Both exceptions still extend `LibTmuxException`. `requireAlive()` still throws
when tmux refuses the probe and preserves transport failures. The old names
have no forwarding aliases.

```java
server.requireAlive();
server.isAlive();                      // → true
```

### Failed live reads

Live listings, finders and snapshot capture throw when a read fails, including
an absent daemon. A missing object in a successful capture still produces an
empty `Optional`. Catch `LibTmuxException` when failed reads require recovery;
do not treat them as an empty server. `server.cmd(...)` still returns a
completed nonzero exit as result data, while `server.run(...)` throws.

A finder requires a running daemon. `server.newSession("build")` can start one;
[`BuildAWorkspace`](examples/src/main/java/io/github/libtmux/examples/BuildAWorkspace.java)
shows how to handle both an existing session and an endpoint with no daemon.

### Discarded replacement handles

Error Prone flags discarded results from `Session.rename`, `Window.rename`,
`Pane.retitle` and entity `refresh` methods through `@CheckReturnValue`. Retain
the returned handle to read the updated capture; the original remains
unchanged.

```java
Session renamed = session.rename("build");
renamed.name();                        // → build
```
