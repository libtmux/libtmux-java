# Getting started

Every snippet here is executed by `ExamplesTest`. If one stops working the build
fails, rather than the page quietly going stale.

Every Java block in this guide runs against a real tmux server when the build
runs, and every value shown after a `→` is asserted.

## Reaching a server

A `Server` is a client, not the tmux process. Closing one closes your connection;
it never ends anybody's sessions.

```java
ServerConfig config = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(socket))
        .build();

try (Server server = Server.open(config)) {
    Session session = server.newSession("demo");
    Window window = session.newWindow("build");
    Pane pane = window.split();

    pane.sendLine("echo hello from libtmux");
}
```

By default each command is one tmux process. One switch makes them share a
persistent client instead — see [execution modes](execution-modes.md), which has
the measured difference and says what else is worth reading.

`Server.open` owns the transport it creates and closes it. `Server.using` borrows
one you own and never closes it, so several servers can share a transport.

To end the tmux server itself, ask plainly:

```java
server.killServer();
```

## Describing what you create

Sessions, windows and panes are all made the same way: call it plainly, describe
it with a lambda, or hand it a description you built earlier.

```java
Session build = server.newSession(s -> s.named("build").firstWindowNamed("editor"));
Window logs = build.newWindow(w -> w.named("logs").running("sleep", "30"));

build.name();                              // → build
build.refresh().windows().get(0).name();   // → editor
logs.name();                               // → logs
```

A new window or pane is selected, because that is what `new-window` and
`split-window` do. Say `detached()` to leave the current one where it is. A
session is the exception and is always detached: `new-session` attaches unless
told not to, and attaching needs a terminal that a build, a service or an agent
does not have.

## Describing a split

`split()` takes tmux's defaults. Anything else is described by a lambda over a
builder:

```java
Pane side = pane.split(s -> s.toRight().percent(30));
Pane app = pane.split(s -> s.running("sleep", "30").in(directory));

side.edges().right();                      // → true
pane.window().refresh().panes().size();    // → 3
```

A description is also a value, so one can be named and applied wherever it fits:

```java
SplitSpec sidebar = SplitSpec.builder().toRight().percent(25).build();

Pane leftSide = session.newWindow("left").split(sidebar);
Pane rightSide = session.newWindow("right").split(sidebar);

leftSide.edges().right();                  // → true
rightSide.edges().right();                 // → true
```

A size is one thing with two spellings — `cells(5)` or `percent(30)` — so there
is no size-and-percentage pair to hold consistent. What runs in the pane is one
choice too: a shell, a command, or nothing at all. tmux rejects a command on an
empty pane, so no spec can carry both.

Options that arrived in tmux 3.7 — an empty pane, keeping a pane after its
command exits, per-pane styles — raise `UnsupportedTmuxVersion` on an older
server rather than being quietly dropped:

```java
if (!server.version().atLeast(new TmuxVersion(3, 7, ""))) {
    assertThrows(UnsupportedTmuxVersion.class, () -> pane.split(s -> s.empty()));
}
```

Being told is the point. A split that silently ignored `empty()` would hand back
a pane with a shell in it, and nothing downstream could tell that apart from the
pane that was asked for.

## A capture is a moment

Accessors read tmux once and hand back handles over what they saw. Walking the
hierarchy afterwards issues no commands at all:

```java
for (Session session : server.sessions()) {
    for (Window window : session.windows()) {
        for (Pane pane : window.panes()) {
            // The window a pane reports is the one it was reached through.
            pane.window().id().equals(window.id());   // → true
        }
    }
}

// One read. Walking it asked tmux nothing further.
server.sessions().get(0).windows().size();   // → 1
```

That is deliberate. tmux offers no transaction across separate listings, so a
traversal that re-queried could observe a hierarchy that never existed. To see
newer state, take a new capture with `refresh()`.

`server.snapshot()` is the strict form: it raises when a listing fails. The list
accessors are lenient and answer with an empty list, which is the long-standing
libtmux contract. Use `isAlive()` or `raiseIfDead()` when you need to tell an
empty server from an absent one.

## Identity survives change

A handle's identity is what a user cannot change. A session is its server and its
id, so renaming it does not produce a different session. A window is its
*winlink* — session, index and window together — because a window linked into two
sessions is one window at two positions, and tmux orders and addresses those
separately. `Window.id()` compares the underlying window across links.

## Options and hooks

A scope is chosen when you take the view, so you cannot read one scope and write
another:

```java
server.globalOptions().set("base-index", "1");

session.options().get("base-index").orElseThrow();   // → 1
```

`get` reports the value tmux will act on, inherited when the scope does not set
it. `all()` answers the narrower question — what this scope sets itself.

## Running several commands

A batch is one tmux invocation, and every operation gets its own outcome:

```java
BatchResult result = server.batch()
        .add("new-window", "-d", "-n", "one")
        .add("new-window", "-d", "-n", "two")
        .run();

result.succeeded();                        // → true
result.operations().get(0).outcome();      // → COMPLETE
result.operations().get(1).outcome();      // → COMPLETE
```

tmux discards a group after its first failure, so a single exit status cannot say
which command failed or which never ran. Each operation is reported as
`COMPLETE`, `FAILED`, `SKIPPED` or `UNKNOWN`.

A chain is the same machinery where each step acts on what the last one made,
using tmux's own current-target following:

```java
server.chain()
        .newWindow("built")
        .splitLeftRight()
        .sendLine("echo chained")
        .run();

// One request made the window and split it, with no round trip to learn its id.
server.windows().stream().anyMatch(w -> w.name().equals("built"));   // → true
```

No step names a target, and no round trip is needed to learn the id of something
just created.

## Watching output

A control client stays attached and pushes terminal output as it happens:

```java
try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
    List<PaneOutput> seen = new CopyOnWriteArrayList<>();
    client.onOutput(seen::add);

    client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");
}
```

Control-mode requests are independent: a failure discards nothing behind it, and
every reply carries the request that produced it. Attaching is what makes tmux
push output at all.

## Pinning tmux's configuration

A run that reads the developer's own `.tmux.conf` is a run whose behaviour nobody
can predict. Pin one:

```java
Path tmuxConf = Files.writeString(directory.resolve("tmux.conf"), "");

ServerConfig pinned = ServerConfig.builder()
        .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
        .configFile(tmuxConf)
        .build();

pinned.configFile().isPresent();           // → true
```

## Where to next

| you want to                       | read                                          |
| --------------------------------- | --------------------------------------------- |
| make every command cost less      | [execution modes](execution-modes.md)         |
| select things without lambdas     | [filtering](filtering.md)                     |
| read and write tmux's settings    | [options and hooks](options-and-hooks.md)     |
| send several commands at once     | [batching and chaining](batching-and-chaining.md) |
| understand what a handle is       | [snapshots and handles](snapshots-and-handles.md) |
| watch output as it happens        | [streaming](streaming.md)                     |
| test your own code against tmux   | [testing](testing.md)                         |
