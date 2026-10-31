<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-junit5

**A JUnit 5 extension that gives each test its own tmux server and checks its
exit during teardown.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/java/)
[![libtmux-junit5](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-junit5?label=libtmux-junit5)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-junit5)

</div>

For testing your own code against a real tmux. Put
[`@ExtendWith(TmuxExtension.class)`][tmux-extension] on a test class and each
test gets a [`Server`][server] of its own, released during teardown. With
[`FakeTmux`][fake-tmux] there is no tmux at all.

`io.github.libtmux:libtmux-junit5` — [on Maven
Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-junit5).
API reference: [`TmuxExtension`][tmux-extension] and [`FakeTmux`][fake-tmux] on
libtmux.org for the current trunk, [javadoc.io][javadoc] for a released version.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.

**Contents** — [Quick start](#quick-start) · [What the fixture hands
you](#what-the-fixture-hands-you) · [Choosing the
tmux](#choosing-the-tmux-binary) · [Without tmux](#without-tmux) · [Leaves
out](#what-it-leaves-out) · [Support](#support-and-status)

## Quick start

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    testImplementation(platform("io.github.libtmux:libtmux-bom:0.1.0"))
    testImplementation("io.github.libtmux:libtmux-junit5")
}
```

### [`TmuxExtension`][tmux-extension]: a server for each test

Ask for a [`Server`][server] and you get one that is already running, with a
session in it:

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

The documentation suite compiles this class.

### [`TmuxSocketPath`][socket-path]: the socket, for code that takes a path

Ask for a `TmuxSocketPath` and you get the socket:

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

### [`Server`][server]: running, with one session

The server is already running, with one session in it, on a socket of its own:

```java
// Given: Server server
server.sessions().size();                         // → 1
server.sessions().get(0).name();                  // → libtmux
server.sessions().get(0).windows().size();        // → 1
```

Do whatever you like to it. The next test gets a different server:

```java
// Given: Server server
server.newSession("scratch");
server.sessions().get(0).newWindow("more");

server.sessions().size();                         // → 2
server.hasSession("scratch");                     // → true
```

### `TmuxSocketPath`: under this port's own root

The socket is never the default one:

```java
// Given: Path socket
socket.toString().startsWith("/tmp/libtmux-java-test/");   // → true
socket.getFileName().toString();                           // → s
```

### What it guarantees

- **A server per test, never shared.** All state lives in the extension store,
  never in fields. JUnit reuses one extension instance for every test in a
  class, so a field would be shared the moment tests run in parallel.
- **A shutdown hook handles JVM termination.** A hard kill such as `SIGKILL`
  skips the hook and can leave servers running until a later fixture run.
- **A killed run is swept by a later run sharing the fixture root.** Before
  making its first server, a run sweeps servers whose owner is gone. It finds
  them in the process table, since a
  killed run never updates a registry. A live owner is never touched.
- **Cleanup failures fail the test.** If a fixture's tmux will not die, the
  test fails and the socket stays, because a live daemon still owns it.

The measurements, and the two designs that lost, are in
[`docs/decisions/0006`](../docs/decisions/0006-real-tmux-junit5-fixture-lifecycle.md).

## Choosing the tmux binary

### `-Dlibtmux.tmux`: run the suite against another release

```console
$ ./gradlew test -Dlibtmux.tmux=/path/to/tmux
```

That is how one suite runs against a whole matrix of releases; see
[`tools/tmux-matrix.sh`](../tools/tmux-matrix.sh).

## Without tmux

### [`FakeTmux`][fake-tmux]: a server that needs no tmux

For code whose interest in tmux ends at the calls it makes. It answers as tmux
does:

- Listings are rendered from the templates the library sends, so a snapshot of
  it is a real snapshot and every handle works.
- A handle is refused once the server is replaced, as tmux refuses one.
- A group stops at its first failure.
- It models sessions, windows, panes and what each pane shows. It does not run
  a shell, so keys sent to a pane are only recorded.

```java
FakeTmux tmux = new FakeTmux();
PaneId editor = tmux.addSession("work");
tmux.show(editor, "$ make", "make: Nothing to be done.");

try (Server server = tmux.server()) {
    Pane pane = server.pane(editor).orElseThrow();
    pane.capture().get(1);            // → make: Nothing to be done.
    pane.sendLine("make test");
}

tmux.sent().getLast().getFirst();     // → send-keys
```

### [`ControlClient`][control-client]: a control-mode server too

`server.control(session)` attaches a client the fake answers, and `output`
pushes what a pane wrote, so code that reads an
[`EventSubscription`][subscription] can be tested without tmux:

```java
FakeTmux tmux = new FakeTmux();
PaneId pane = tmux.addSession("work");

try (Server server = tmux.server();
        ControlClient client = server.control(server.sessions().getFirst());
        EventSubscription<PaneOutput> output = client.subscribeOutput(8)) {
    tmux.output(pane, "built");
    Delivery.kept(output.next(Duration.ofSeconds(5)).orElseThrow()).data();   // → built
}
```

- `notify` pushes a notification line, such as `%window-renamed @1 logs`.
- `restart()` replaces the server under every handle made so far, and ends every
  attached control client, which is how to test code that has to survive one.
- For behaviour that depends on tmux itself, such as what a release does with a
  flag or how a shell echoes, use the extension above.

## What it leaves out

- **Tests of libtmux itself.** The fixture is for code that uses libtmux.
- **A shell in the fake.** [`FakeTmux`][fake-tmux] records keys; it does not
  type them.
- **The default socket.** Every server lives under this port's own root, so
  another suite's leftovers do not become your intermittent failures.

## Support and status

- **Another tmux-using suite?** Give each suite a socket root that names it, and
  never use the default socket. [`CONTRIBUTING.md`](../.github/CONTRIBUTING.md)
  has this repository's own rule and the failure modes behind it.
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.

## Documentation

- [Testing with real tmux](../docs/guide/testing.md)
- [`libtmux`](../libtmux/README.md) — the library this gives you a server for
- [Root README](../README.md)

[control-client]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-controlclient-controlclient/
[fake-tmux]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-faketmux-faketmux/
[javadoc]: https://javadoc.io/doc/io.github.libtmux/libtmux-junit5
[server]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-server-server/
[socket-path]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-tmuxsocketpath-tmuxsocketpath/
[subscription]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-eventsubscription-eventsubscription/
[tmux-extension]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-junit5-tmuxextension-tmuxextension/
