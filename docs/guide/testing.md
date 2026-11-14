# Testing with real tmux

`libtmux-junit5` gives each test its own tmux server on its own socket, and takes
it back afterwards.

```java
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TmuxExtension.class)
final class MyTest {

    @Test
    void aWindowCanBeMade(Server server) {
        server.sessions().get(0).newWindow("built");
    }
}
```

The extension resolves `Server` and `TmuxSocketPath`. It never claims a bare
`Path` parameter, so another extension is free to resolve those.

What that hands you:

```java
// Given: Server server, Path socket
server.sessions().size();                          // → 1
server.sessions().get(0).name();                   // → libtmux
socket.startsWith("/tmp/libtmux-java-test/");      // → true
```

## What it guarantees

Before the test, it creates a private directory and socket, registers the object
that owns them *before* any process exists, starts tmux with an explicitly empty
config, and checks tmux agrees about which socket it is listening on.

Afterwards it proves the daemon exited by asking, rather than assuming a kill won
its race, and only then unlinks the socket and removes the directory. If exit
cannot be proved, it preserves the failure rather than deleting a socket a live
daemon still owns.

Teardown runs whether the test passed, failed or errored.

A shutdown hook also cleans up on normal JVM termination. A hard kill such as
`SIGKILL` skips it and can leave tmux running. Before its first fixture starts,
a later run sharing the fixture root sweeps servers whose owning JVM is gone.

## Isolation from your own tmux

Every test task runs with `TMUX`, `TMUX_PANE` and `LIBTMUX_SOCKET*` removed, and
with a short `TMUX_TMPDIR` under `/tmp/libtmux-java-test/` that is also the
JVM's temporary directory. A command that omits its `-S` therefore cannot reach
the tmux you are working in.

When a task ends, even by failing, a finalizer stops each server that answers on
a socket anywhere in that directory and fails the build naming it. A test JVM
that was not started this way, such as an IDE run, refuses to start. Explicitly
named-socket tests capture the server process and reported inode, then reclaim
only that inode after the process has exited.

The build enforces this, rather than every test remembering, because the code
under test is exactly what is allowed to be wrong.

## Without tmux

When the code under test only makes calls, a real server is more than it needs.
`FakeTmux` answers the library the way tmux does — listings, handles, fences,
groups — from a model of sessions, windows and panes, and records every command
it was sent. See [the module README](../../libtmux-junit5/README.md#without-tmux).

## Running against every supported tmux

The real-tmux suites run against each release the library supports, given a tree
of tmux builds with one directory per lane:

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

Each lane checks that the running server is the tmux it names.

Two version differences are handled inside the library and are worth knowing
about, because both are silent:

- `pane_floating_flag` does not exist before tmux 3.7, and the format expands to
  nothing rather than to zero. `Pane.floating()` is an `Optional` that is empty
  when the running tmux cannot say, rather than defaulting to `false`.
- Exact tmux 3.7 can end the server on an unnamed `break-pane` and ignores an
  explicit name in its multi-pane branch. `Pane.breakOut()` refuses there;
  `Pane.breakOut(name)` supplies and then reapplies the explicit name.

Control needs tmux 3.7 or newer, and named buffers and managed paste need 3.4.
Neither changes the core 3.3a minimum.
