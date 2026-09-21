# Snapshots and handles

Every snippet here is executed by `ExamplesTest`.

## A capture is a moment

An accessor reads tmux once and hands back handles over what it saw. Walking the
hierarchy afterwards issues no commands at all:

```java
// Given: Server server
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

`refresh()` is how to look again. Every live listing, finder and snapshot
capture throws when it fails. An absent daemon throws
`ServerNotRunningException`; any other failed capture throws
`LibTmuxException`. Empty collections and optionals mean a successful capture
contained no matches. This changes the earlier alpha behavior that hid failed
reads behind empty results. Use `isAlive()` when you only need to probe
whether a daemon answers; transport failures still throw.

## What a snapshot stores

`info()` is that moment. `name()`, `title()`, and `size()` read it. A method
that sends keys, renames, or waits talks to tmux and does not update `info()`.

The formats read into the snapshot are:

| Object | Formats |
| --- | --- |
| Server | `pid`, `version` |
| Session | `session_id`, `session_name`, `session_attached`, `session_windows` |
| Window | `session_id`, `window_id`, `window_index`, `window_name`, `window_active`, `window_panes`, `window_linked`, `window_width`, `window_height`, `window_layout` |
| Pane | `session_id`, `window_id`, `window_index`, `pane_id`, `pane_index`, `pane_active`, `pane_current_command`, `pane_width`, `pane_height`, `pane_left`, `pane_top`, `pane_title`, `pane_current_path`, `pane_pid`, `pane_at_top`, `pane_at_bottom`, `pane_at_left`, `pane_at_right`, and `pane_floating_flag` on tmux 3.7 and later |
| Client | `client_name`, `session_id` |

Anything else is a live read. `expand` formats one string. `variables` reads
named formats for one target. `paneFields` reads named formats for every pane.
Those three are the supported way out of the table above.

```java
// Given: Server server
Session session = server.newSession("captured");

session.info().name();       // → captured
session.info().windows();    // → 1
```

## Identity is what a user cannot change

A session is its server and its id, so renaming does not produce a different
session. A window is its *winlink* — session, index and window together — because
a window linked into two sessions is one window at two positions, and tmux
orders and addresses those separately.

```java
// Given: Server server
Session before = server.sessions().get(0);
String originalName = before.name();
Session renamed = before.rename("something-else");

renamed.name();                    // → something-else
before.equals(renamed);            // → true
before.name().equals(originalName); // → true
```

Retain the returned handle to read the changed name. The earlier handle keeps
its original captured state, even after a successful mutation or `refresh()`.
Equality still compares identity, so the two handles above compare equal.

One rule decides what a changing method returns: **a handle when it produces
one you need, and nothing otherwise.** A created window or pane is new, so
`newWindow`, `split` and `breakOut` return it. A renamed or retitled object is
found again by its new label, so `rename` and `retitle` return it under that
label. Every other change — `select`, `resizeTo`, `moveTo`, `selectLayout`,
`send` — returns nothing, and the handle you hold still describes the moment it
was captured. Call `refresh()` for the state after:

```java
// Given: Window window
window.resizeTo(new Dimensions(90, 20));
Window captured = window.refresh();
captured.resizeTo(new Dimensions(100, 30));

captured.size();             // → 90x20
captured.refresh().size();   // → 100x30
```

The first size is set rather than inherited on purpose. A new window's default
is the client's size, less a row for the status line on tmux 3.2a and not on
3.3 or later, so a snippet that printed that default would be telling the truth
on only some of the releases this supports.

`Session.rename`, `Window.rename`, `Pane.retitle`, and each handle's `refresh`
carry `@CheckReturnValue`. Error Prone rejects a call that discards their results.
If the effect alone is needed, make that choice explicit:

```java
// Given: Session session
var unused = session.rename("effect-only");
```

`Client.refresh()` returns an `Optional<Client>` because a client can detach
while its daemon stays reachable. Empty means that client is gone; a failed
capture still throws. The other handles' `refresh()` methods return a
replacement or throw `ObjectDoesNotExistException` when their target is gone
from a server that still answers. An absent daemon throws
`ServerNotRunningException` there too, like every other read. None changes the
previous handle.

`Window.id()` compares the underlying window across links.

Names are state, and tmux moves them on its own: `automatic-rename` takes a
window's name from what its pane is running, so a window renames itself when a
program starts. Compare ids when you mean identity.

## Reaching past the snapshot

A snapshot carries the fields worth carrying. `expand` reaches everything else
tmux knows, including fields from a release this library has never heard of:

```java
// Given: Server server, Pane pane
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
// Given: Pane pane
pane.sendLine("echo captured");

List<String> everything = pane.capture(c -> c.fromStartOfHistory());
List<String> recent = pane.capture(c -> c.from(-10));

// The history contains at least what is on screen, whatever the shell has printed.
recent.size() <= everything.size();                             // → true
```
