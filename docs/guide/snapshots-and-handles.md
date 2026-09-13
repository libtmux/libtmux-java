# Snapshots and handles

Every snippet here is executed by `ExamplesTest`.

## A capture is a moment

An accessor reads tmux once and hands back handles over what it saw. Walking the
hierarchy afterwards issues no commands at all:

```java
for (Session session : server.sessions()) {
    for (Window window : session.windows()) {
        for (Pane pane : window.panes()) {
            assertEquals(window.id(), pane.window().id());
        }
    }
}
```

That is deliberate. tmux offers no transaction across separate listings, so a
traversal that re-queried could observe a hierarchy that never existed — a window
in one listing and its panes from after it closed.

A capture costs two tmux commands: one asks which server this is, and one runs the
four listings as a group fenced against that answer. Because tmux runs a group
inside the server, the rows cannot come from two of them, and a server replaced
under the capture is refused rather than half-read. What that costs is measured in
[`docs/benchmarks/operations.md`](../benchmarks/operations.md).

`refresh()` is how to look again. Every live listing, finder and snapshot capture
throws `LibTmuxException` when capture fails, including an absent daemon. Empty
collections and optionals mean a successful capture contained no matches. This
changes the earlier alpha behavior that hid failed reads behind empty results.
Use `isAlive()` when you only need to probe whether a daemon answers; transport
failures still throw.

## Identity is what a user cannot change

A session is its server and its id, so renaming does not produce a different
session. A window is its *winlink* — session, index and window together — because
a window linked into two sessions is one window at two positions, and tmux
orders and addresses those separately.

```java
Session before = server.sessions().get(0);
Session renamed = before.rename("something-else");

renamed.name();                    // → something-else
before.equals(renamed);            // → true
```

The name changed; the session did not. Identity is the id tmux assigned, which a
user cannot edit, so a handle stays valid across a rename.

`Window.id()` compares the underlying window across links.

Names are state, and tmux moves them on its own: `automatic-rename` takes a
window's name from what its pane is running, so a window renames itself when a
program starts. Compare ids when you mean identity.

## Reaching past the snapshot

A snapshot carries the fields worth carrying. `expand` reaches everything else
tmux knows, including fields from a release this library has never heard of:

```java
pane.expand("#{session_name}:#{window_index}.#{pane_index}");   // → libtmux:0.0

server.expand("#{version}").isEmpty();                          // → false
```

Available on `Server`, `Session`, `Window` and `Pane`, each resolving in its own
context. A format that means nothing there comes back empty rather than failing.

## Reading a pane

`capture()` reads what is on screen. To reach what scrolled off it, describe the
range — lines count from the top of the visible area, and negatives climb into
the history:

```java
pane.sendLine("echo captured");

List<String> everything = pane.capture(c -> c.fromStartOfHistory());
List<String> recent = pane.capture(c -> c.from(-10));

// The history contains at least what is on screen, whatever the shell has printed.
recent.size() <= everything.size();                             // → true
```
