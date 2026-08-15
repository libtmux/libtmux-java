# Testing with real tmux

`libtmux-junit5` gives each test its own tmux server on its own socket, and takes
it back afterwards.

```java
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

Teardown runs whether the test passed, failed or errored. That is checked by
running a deliberately failing test in a nested engine and inspecting what it
left behind — a test cannot watch its own teardown.

## Isolation from your own tmux

Every test task runs with a build-local `TMUX_TMPDIR` and with `TMUX` and
`TMUX_PANE` removed. A command that omits its `-S` therefore cannot reach the
tmux you are working in.

This is enforced by the build rather than by every test remembering, because the
code under test is exactly what is allowed to be wrong. The suite asserts the
quarantine is in place.

## Running against every supported tmux

The real-tmux suites run against each release the library supports, given a tree
of tmux builds with one directory per lane:

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

Each lane declares which tmux it covers and the suite checks the running server
agrees. Without that, a lane that silently ignored the configured binary would
run against whatever is on `PATH` and report exactly the same green.

Two version differences are handled inside the library and are worth knowing
about, because both are silent:

- `pane_floating_flag` does not exist before tmux 3.7, and the format expands to
  nothing rather than to zero. `Pane.floating()` is an `Optional` that is empty
  when the running tmux cannot say, rather than defaulting to `false`.
- tmux 3.7 exactly ends the whole server when `break-pane` has to name the new
  window itself, and silently discards a name it is given. `Pane.breakOut()`
  works around both.
