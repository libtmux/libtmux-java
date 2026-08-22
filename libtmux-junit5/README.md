# libtmux-junit5

**A JUnit 5 extension that gives each test its own tmux server, and guarantees it
is gone afterwards.**

For testing *your* code against real tmux — not for testing libtmux.

`io.github.libtmux:libtmux-junit5` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-junit5).

> **Alpha.** The API will change without notice.

## Install

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    testImplementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.6"))
    testImplementation("io.github.libtmux:libtmux-junit5")
}
```

## Use it

```java
@ExtendWith(TmuxExtension.class)
class MyToolTest {

    @Test
    void itRunsSomethingInAPane(Server server) {
        Session session = server.sessions().get(0);   // the fixture made one, named "libtmux"
        Pane pane = session.windows().get(0).panes().get(0);

        pane.sendLine("echo hello");

        assertTrue(pane.capture().stream().anyMatch(line -> line.contains("hello")));
    }
}
```

Ask for a `Server` and you get one that is already running with a session in it.
Ask for a `TmuxSocketPath` and you get the socket, for code that takes a path:

```java
@ExtendWith(TmuxExtension.class)
class MyOtherTest {

    @Test
    void myCodeConnectsForItself(TmuxSocketPath socket) {
        ServerConfig mine = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket.path()))
                .build();
        // ... hand `mine` to whatever you are testing
    }
}
```

## What the fixture hands you

A server that is already running, with one session in it, on a socket of its own:

```java
server.sessions().size();                         // → 1
server.sessions().get(0).name();                  // → libtmux
server.sessions().get(0).windows().size();        // → 1
```

Do whatever you like to it. The next test gets a different server:

```java
server.newSession("scratch");
server.sessions().get(0).newWindow("more");

server.sessions().size();                         // → 2
server.hasSession("scratch");                     // → true
```

And the socket is under this port's own root, never the default one:

```java
socket.toString().startsWith("/tmp/libtmux-java-test/");   // → true
socket.getFileName().toString();                           // → s
```

## What it guarantees

**A server per test, never shared.** All state lives in the extension store, never
in fields. JUnit reuses one extension instance for every test in a class, so a
field would be shared the moment tests run in parallel, and one test's teardown
would reach another test's server.

**No server outlives the run — even one killed outright.** Teardown normally runs
from `afterEach`. But a finalizer only runs if the process lives long enough to
run it, and a killed test JVM leaves tmux servers behind whose sockets the
system's temp cleaner later removes, making them unreachable by any means except a
signal. So:

- the socket path names the **owning JVM's pid**;
- a shutdown hook ends this JVM's own servers on termination;
- before making its first server, a run **sweeps servers whose owner is gone**,
  reading the process table rather than any registry the killed run never got to
  update.

A live owner is never touched, so Gradle's per-module workers and a tmux version
matrix can all share one root safely. The measurements, and the two designs that
lost, are in [`docs/spikes/22`](../docs/spikes/22-abandoned-servers.md).

**Cleanup failures are loud.** If a fixture's tmux will not die, the test fails
rather than quietly unlinking a socket a live daemon still owns.

## Choosing the tmux binary

```console
$ ./gradlew test -Dlibtmux.tmux=/path/to/tmux
```

Which is how one suite runs against a whole matrix of releases — see
[`scripts/tmux-matrix.sh`](../scripts/tmux-matrix.sh).

## Keep servers off other people's sockets

If your project also runs another tmux-using suite, give each one a socket root
that names it, and never use the default socket. This repository's own rule and
the failure modes behind it are in
[`CONTRIBUTING.md`](../.github/CONTRIBUTING.md); the short version is that
another suite's leftovers become your intermittent failures.

## Next

- [Testing with real tmux](../docs/guide/testing.md)
- [`libtmux`](../libtmux/) — the library this gives you a server for
- [Root README](../README.md)
