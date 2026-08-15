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

`refresh()` is how to look again. `server.snapshot()` is the strict form: it
raises when a listing failed, where the list accessors answer with an empty list.
Use `isAlive()` or `raiseIfDead()` to tell an empty server from an absent one.

## Identity is what a user cannot change

A session is its server and its id, so renaming does not produce a different
session. A window is its *winlink* — session, index and window together — because
a window linked into two sessions is one window at two positions, and tmux
orders and addresses those separately.

```java
Session before = server.sessions().get(0);
Session renamed = before.rename("something-else");

assertEquals(before, renamed);     // the same session, differently labelled
```

`Window.id()` compares the underlying window across links.

Names are state, and tmux moves them on its own: `automatic-rename` takes a
window's name from what its pane is running, so a window renames itself when a
program starts. Compare ids when you mean identity.

## Reaching past the snapshot

A snapshot carries the fields worth carrying. `expand` reaches everything else
tmux knows, including fields from a release this library has never heard of:

```java
String where = pane.expand("#{session_name}:#{window_index}.#{pane_index}");
String version = server.expand("#{version}");
```

Available on `Server`, `Session`, `Window` and `Pane`, each resolving in its own
context. A format that means nothing there comes back empty rather than failing.

## Reading a pane

`capture()` reads what is on screen. To reach what scrolled off it, describe the
range — lines count from the top of the visible area, and negatives climb into
the history:

```java
List<String> everything = pane.capture(c -> c.fromStartOfHistory());
List<String> recent = pane.capture(c -> c.from(-10));
```
