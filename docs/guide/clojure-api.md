# libtmux-clojure API

Generated from public namespace and var metadata; do not edit by hand.
Each heading links to its current Clojure source.

Read the [guide](clojure.md) for runnable programs and ownership.
The [captured-value reference](clojure-reference.md) documents map keys.
Functions ending in `!` may cross the external-state boundary.

## Namespaces

- [`libtmux.core`](#libtmuxcore)
- [`libtmux.data`](#libtmuxdata)
- [`libtmux.async`](#libtmuxasync)
- [`libtmux.control`](#libtmuxcontrol)

## [`libtmux.core`][libtmux.core]

Explicit tmux effects through [`open!`][api:libtmux.core/open!] and
[`snapshot!`][api:libtmux.core/snapshot!].
Captured maps retain opaque Java references; [`libtmux.data`][api:libtmux.data]
traverses them locally.

The functions that act on a server are grouped on
[`open!`][api:libtmux.core/open!]. Sessions, windows
and panes each have a directory below, grouped by what you do with them.

### What is on here: sessions

A session is a captured map with `:session/` keys whose `:tmux/ref` is the Java
Session. Functions that act on a session take that reference, and functions
that read a capture take the map. They fall into a few groups:

**Finding sessions.** [`sessions!`][api:libtmux.core/sessions!] takes a fresh
snapshot and returns every
captured session, and [`find-session!`][api:libtmux.core/find-session!] looks
one up by exact name and returns
`nil` when tmux has none. [`snapshot!`][api:libtmux.core/snapshot!] captures
sessions with their windows and
panes, and [`refresh!`][api:libtmux.core/refresh!] reacquires one session from
its reference.

- Acquire: [`sessions!`][api:libtmux.core/sessions!],
[`find-session!`][api:libtmux.core/find-session!],
[`snapshot!`][api:libtmux.core/snapshot!]
- Refresh: [`refresh!`][api:libtmux.core/refresh!]

**Selecting and comparing.** Filter captured sessions with an ordinary Clojure
predicate or with [`libtmux.data/matching`][api:libtmux.data/matching], then
take the result with
[`libtmux.data/exactly-one`][api:libtmux.data/exactly-one] or
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none].
[`libtmux.data/entity-key`][api:libtmux.data/entity-key] and
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?] compare identities
without asking tmux, and [`libtmux.data/data`][api:libtmux.data/data] strips the
references to leave
plain EDN.

- Filter: [`libtmux.data/matching`][api:libtmux.data/matching],
[`libtmux.data/predicate`][api:libtmux.data/predicate]
- Take one: [`libtmux.data/exactly-one`][api:libtmux.data/exactly-one],
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none]
- Compare: [`libtmux.data/entity-key`][api:libtmux.data/entity-key],
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?]
- Plain EDN: [`libtmux.data/data`][api:libtmux.data/data]

**Creating, renaming and removing.**
[`new-session!`][api:libtmux.core/new-session!] creates a detached session
from an options map, rejects unknown keys, and returns a fresh capture of it.
[`rename!`][api:libtmux.core/rename!] renames a session or window, and
[`kill!`][api:libtmux.core/kill!] removes a session,
window or pane.

- Create: [`new-session!`][api:libtmux.core/new-session!]
- Rename: [`rename!`][api:libtmux.core/rename!]
- Remove: [`kill!`][api:libtmux.core/kill!]

**Working inside a session.** [`libtmux.data/windows`][api:libtmux.data/windows]
and [`libtmux.data/panes`][api:libtmux.data/panes]
read a session's windows and panes from a capture without asking tmux again, and
[`libtmux.data/active-window`][api:libtmux.data/active-window] and
[`libtmux.data/active-pane`][api:libtmux.data/active-pane] pick the active
ones. [`new-window!`][api:libtmux.core/new-window!] adds a window,
[`link-window!`][api:libtmux.core/link-window!] links an existing window
into the session, and [`move-window!`][api:libtmux.core/move-window!] moves one
to it at an index.

- Read a capture: [`libtmux.data/windows`][api:libtmux.data/windows],
[`libtmux.data/panes`][api:libtmux.data/panes],
[`libtmux.data/active-window`][api:libtmux.data/active-window],
[`libtmux.data/active-pane`][api:libtmux.data/active-pane]
- Add windows: [`new-window!`][api:libtmux.core/new-window!],
[`link-window!`][api:libtmux.core/link-window!],
[`move-window!`][api:libtmux.core/move-window!]

**Clients.** [`switch-client!`][api:libtmux.core/switch-client!] points a client
from [`clients!`][api:libtmux.core/clients!] at a session.
[`libtmux.control/attach!`][api:libtmux.control/attach!] attaches a control
connection to a captured session;
it needs tmux 3.7 or newer.

- Switch: [`switch-client!`][api:libtmux.core/switch-client!],
[`clients!`][api:libtmux.core/clients!]
- Control: [`libtmux.control/attach!`][api:libtmux.control/attach!]

**Options, hooks, and environment.** [`options!`][api:libtmux.core/options!]
reads the options set locally
on a session, or the effective inherited ones;
[`set-option!`][api:libtmux.core/set-option!] sets an override
and [`unset-option!`][api:libtmux.core/unset-option!] restores inheritance.
[`hooks!`][api:libtmux.core/hooks!] returns its hooks, which
[`set-hook!`][api:libtmux.core/set-hook!] replaces,
[`append-hook!`][api:libtmux.core/append-hook!] extends and
[`unset-hook!`][api:libtmux.core/unset-hook!] removes.
[`environment!`][api:libtmux.core/environment!] returns the values and the names
marked removed;
[`set-environment!`][api:libtmux.core/set-environment!] sets a value,
[`unset-environment!`][api:libtmux.core/unset-environment!] drops the local
entry
so the name is inherited, and
[`remove-environment!`][api:libtmux.core/remove-environment!] masks the name
from new
processes. [`expand!`][api:libtmux.core/expand!] evaluates a tmux format at the
session.

- Options: [`options!`][api:libtmux.core/options!],
[`set-option!`][api:libtmux.core/set-option!],
[`unset-option!`][api:libtmux.core/unset-option!]
- Hooks: [`hooks!`][api:libtmux.core/hooks!],
[`set-hook!`][api:libtmux.core/set-hook!],
[`append-hook!`][api:libtmux.core/append-hook!],
[`unset-hook!`][api:libtmux.core/unset-hook!]
- Environment: [`environment!`][api:libtmux.core/environment!],
[`set-environment!`][api:libtmux.core/set-environment!],
[`unset-environment!`][api:libtmux.core/unset-environment!],
[`remove-environment!`][api:libtmux.core/remove-environment!]
- Formats: [`expand!`][api:libtmux.core/expand!]

### What is on here: windows

A window is a captured map with `:window/` keys, one for each session that
holds it, so a window linked into two sessions appears twice. Its `:tmux/ref` is
the Java Window. Functions that act on a window take that reference, and
functions that read a capture take the map. They fall into a few groups:

**Finding windows.** [`windows!`][api:libtmux.core/windows!] takes a fresh
snapshot and returns every
captured window occurrence. [`libtmux.data/windows`][api:libtmux.data/windows]
reads a snapshot's or a
session's windows without asking tmux again, and
[`libtmux.data/active-window`][api:libtmux.data/active-window]
picks a session's active one. [`snapshot!`][api:libtmux.core/snapshot!] captures
windows with their panes,
and [`refresh!`][api:libtmux.core/refresh!] reacquires one window, keeping its
placement.

- Acquire: [`windows!`][api:libtmux.core/windows!],
[`snapshot!`][api:libtmux.core/snapshot!]
- Read a capture: [`libtmux.data/windows`][api:libtmux.data/windows],
[`libtmux.data/active-window`][api:libtmux.data/active-window]
- Refresh: [`refresh!`][api:libtmux.core/refresh!]

**Selecting and comparing.** Filter captured windows with an ordinary Clojure
predicate or with [`libtmux.data/matching`][api:libtmux.data/matching], then
take the result with
[`libtmux.data/exactly-one`][api:libtmux.data/exactly-one] or
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none].
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?] compares the
physical window and
[`libtmux.data/same-link?`][api:libtmux.data/same-link?] also its session and
index, both without asking
tmux. [`libtmux.data/data`][api:libtmux.data/data] strips the references to
leave plain EDN.

- Filter: [`libtmux.data/matching`][api:libtmux.data/matching],
[`libtmux.data/predicate`][api:libtmux.data/predicate]
- Take one: [`libtmux.data/exactly-one`][api:libtmux.data/exactly-one],
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none]
- Compare: [`libtmux.data/entity-key`][api:libtmux.data/entity-key],
[`libtmux.data/link-key`][api:libtmux.data/link-key],
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?],
[`libtmux.data/same-link?`][api:libtmux.data/same-link?]
- Plain EDN: [`libtmux.data/data`][api:libtmux.data/data]

**Creating, renaming and removing.**
[`new-window!`][api:libtmux.core/new-window!] creates a window in a
session from an options map, rejects unknown keys, and returns a fresh capture
of it. [`rename!`][api:libtmux.core/rename!] renames a window or session, and
[`kill!`][api:libtmux.core/kill!] removes a window,
session or pane.

- Create: [`new-window!`][api:libtmux.core/new-window!]
- Rename: [`rename!`][api:libtmux.core/rename!]
- Remove: [`kill!`][api:libtmux.core/kill!]

**Linking and moving.** [`link-window!`][api:libtmux.core/link-window!] links a
window into a session and
[`unlink-window!`][api:libtmux.core/unlink-window!] removes one placement.
[`move-window!`][api:libtmux.core/move-window!] moves a window to a
session at an index.

- Link: [`link-window!`][api:libtmux.core/link-window!],
[`unlink-window!`][api:libtmux.core/unlink-window!]
- Move: [`move-window!`][api:libtmux.core/move-window!]

**Selecting, sizing and arranging.** [`select!`][api:libtmux.core/select!]
selects a window or pane.
[`resize!`][api:libtmux.core/resize!] sets the cell dimensions of a window or
pane, and [`apply-layout!`][api:libtmux.core/apply-layout!]
applies a tmux layout string to a window.

- Select: [`select!`][api:libtmux.core/select!]
- Size and layout: [`resize!`][api:libtmux.core/resize!],
[`apply-layout!`][api:libtmux.core/apply-layout!]

**Panes in a window.** [`libtmux.data/panes`][api:libtmux.data/panes] reads a
window's panes from a
capture without asking tmux again, and
[`libtmux.data/active-pane`][api:libtmux.data/active-pane] picks its
active one. [`join-pane!`][api:libtmux.core/join-pane!] moves an existing pane
into the window.

- Read a capture: [`libtmux.data/panes`][api:libtmux.data/panes],
[`libtmux.data/active-pane`][api:libtmux.data/active-pane]
- Move a pane in: [`join-pane!`][api:libtmux.core/join-pane!]

**Options and hooks.** [`options!`][api:libtmux.core/options!] reads the options
set locally on a window,
or the effective inherited ones; [`set-option!`][api:libtmux.core/set-option!]
sets an override and
[`unset-option!`][api:libtmux.core/unset-option!] restores inheritance.
[`hooks!`][api:libtmux.core/hooks!] returns its hooks, which
[`set-hook!`][api:libtmux.core/set-hook!] replaces,
[`append-hook!`][api:libtmux.core/append-hook!] extends and
[`unset-hook!`][api:libtmux.core/unset-hook!] removes.
[`expand!`][api:libtmux.core/expand!] evaluates a tmux format at the window.

- Options: [`options!`][api:libtmux.core/options!],
[`set-option!`][api:libtmux.core/set-option!],
[`unset-option!`][api:libtmux.core/unset-option!]
- Hooks: [`hooks!`][api:libtmux.core/hooks!],
[`set-hook!`][api:libtmux.core/set-hook!],
[`append-hook!`][api:libtmux.core/append-hook!],
[`unset-hook!`][api:libtmux.core/unset-hook!]
- Formats: [`expand!`][api:libtmux.core/expand!]

### What is on here: panes

A pane is a captured map with `:pane/` keys, one for each window that holds it.
Its `:tmux/ref` is the Java Pane. Functions that act on a pane take that
reference, and functions that read a capture take the map. They fall into a few
groups:

**Finding panes.** [`panes!`][api:libtmux.core/panes!] takes a fresh snapshot
and returns every captured
pane occurrence. [`libtmux.data/panes`][api:libtmux.data/panes] reads the panes
of a snapshot, session
or window from a capture, and
[`libtmux.data/active-pane`][api:libtmux.data/active-pane] picks the active pane
of a window or session. [`snapshot!`][api:libtmux.core/snapshot!] captures panes
with their windows and
sessions, and [`refresh!`][api:libtmux.core/refresh!] reacquires one pane,
keeping its placement.

- Acquire: [`panes!`][api:libtmux.core/panes!],
[`snapshot!`][api:libtmux.core/snapshot!]
- Read a capture: [`libtmux.data/panes`][api:libtmux.data/panes],
[`libtmux.data/active-pane`][api:libtmux.data/active-pane]
- Refresh: [`refresh!`][api:libtmux.core/refresh!]

**Selecting and comparing.** Filter captured panes with an ordinary Clojure
predicate or with [`libtmux.data/matching`][api:libtmux.data/matching], then
take the result with
[`libtmux.data/exactly-one`][api:libtmux.data/exactly-one] or
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none].
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?] compares the
physical pane and
[`libtmux.data/same-link?`][api:libtmux.data/same-link?] also its window and
session, both without asking
tmux. [`libtmux.data/data`][api:libtmux.data/data] strips the references to
leave plain EDN.

- Filter: [`libtmux.data/matching`][api:libtmux.data/matching],
[`libtmux.data/predicate`][api:libtmux.data/predicate]
- Take one: [`libtmux.data/exactly-one`][api:libtmux.data/exactly-one],
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none]
- Compare: [`libtmux.data/entity-key`][api:libtmux.data/entity-key],
[`libtmux.data/link-key`][api:libtmux.data/link-key],
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?],
[`libtmux.data/same-link?`][api:libtmux.data/same-link?]
- Plain EDN: [`libtmux.data/data`][api:libtmux.data/data]

**Splitting, moving and removing.**
[`split-pane!`][api:libtmux.core/split-pane!] splits a pane from an options
map that picks the direction and size, and returns a fresh capture of the new
pane. [`join-pane!`][api:libtmux.core/join-pane!] moves an existing pane into a
window, and [`kill!`][api:libtmux.core/kill!] removes
a pane.

- Split: [`split-pane!`][api:libtmux.core/split-pane!]
- Move: [`join-pane!`][api:libtmux.core/join-pane!]
- Remove: [`kill!`][api:libtmux.core/kill!]

**Selecting and resizing.** [`select!`][api:libtmux.core/select!] selects a pane
or window, and
[`resize!`][api:libtmux.core/resize!] sets the cell dimensions of a pane or
window.

- Select: [`select!`][api:libtmux.core/select!]
- Size: [`resize!`][api:libtmux.core/resize!]

**Typing into a pane.** [`send-literal!`][api:libtmux.core/send-literal!] sends
text as written and
[`send-keys!`][api:libtmux.core/send-keys!] sends a vector of tmux key names; a
completed send does not mean
the application is ready. [`paste-buffer!`][api:libtmux.core/paste-buffer!]
pastes a buffer that
[`set-buffer!`][api:libtmux.core/set-buffer!] stored.

- Type: [`send-literal!`][api:libtmux.core/send-literal!],
[`send-keys!`][api:libtmux.core/send-keys!]
- Paste: [`paste-buffer!`][api:libtmux.core/paste-buffer!]

**Reading a pane.** [`capture!`][api:libtmux.core/capture!] returns a pane's
lines, with scrollback when
`:from` is `:history`. [`await-text!`][api:libtmux.core/await-text!] waits
within a positive Duration for text
to appear and returns a keyword that names the outcome.
[`libtmux.control/subscribe!`][api:libtmux.control/subscribe!] observes new
output on a control connection, and
[`expand!`][api:libtmux.core/expand!] evaluates a tmux format at the pane.

- Read: [`capture!`][api:libtmux.core/capture!],
[`await-text!`][api:libtmux.core/await-text!]
- Observe: [`libtmux.control/subscribe!`][api:libtmux.control/subscribe!]
- Formats: [`expand!`][api:libtmux.core/expand!]

**Options and hooks.** [`options!`][api:libtmux.core/options!] reads the options
set locally on a pane, or
the effective inherited ones; [`set-option!`][api:libtmux.core/set-option!] sets
an override and
[`unset-option!`][api:libtmux.core/unset-option!] restores inheritance.
[`hooks!`][api:libtmux.core/hooks!] returns its hooks, which
[`set-hook!`][api:libtmux.core/set-hook!] replaces,
[`append-hook!`][api:libtmux.core/append-hook!] extends and
[`unset-hook!`][api:libtmux.core/unset-hook!] removes.

- Options: [`options!`][api:libtmux.core/options!],
[`set-option!`][api:libtmux.core/set-option!],
[`unset-option!`][api:libtmux.core/unset-option!]
- Hooks: [`hooks!`][api:libtmux.core/hooks!],
[`set-hook!`][api:libtmux.core/set-hook!],
[`append-hook!`][api:libtmux.core/append-hook!],
[`unset-hook!`][api:libtmux.core/unset-hook!]

### [`libtmux.core/append-hook!`][libtmux.core]

`(append-hook! value event argv)`

Appends one explicit tmux argv command to a hook.
[`hooks!`][api:libtmux.core/hooks!] returns the ordered command vectors.

### [`libtmux.core/apply-layout!`][libtmux.core]

`(apply-layout! window layout)`

Applies an explicit tmux layout string.
[`resize!`][api:libtmux.core/resize!] sets explicit cell dimensions.

### [`libtmux.core/await-channel!`][libtmux.core]

`(await-channel! server name timeout)`

Waits on an explicit tmux channel while reserving capacity for its release.
Returns `:signalled`, `:timed-out`, or `:server-gone`; interruption propagates.
[`signal-channel!`][api:libtmux.core/signal-channel!] releases this wait;
declare [`libtmux.async/submit!`][api:libtmux.async/submit!] work as `:wait`.

### [`libtmux.core/await-text!`][libtmux.core]

`(await-text! pane text timeout)`

Waits within a positive Duration; returns a keyword describing the outcome.
Use [`capture!`][api:libtmux.core/capture!] to read lines after the wait;
[`send-keys!`][api:libtmux.core/send-keys!] alone proves no completion.

### [`libtmux.core/batch!`][libtmux.core]

`(batch! server operations)`

`(batch! server operations options)`

Runs independent argv commands using a fresh Java Batch and returns positions.
Supply explicit targets. Failed/skipped outcomes are data; transport failure
throws with `:tmux/results.` Use `{:kind :wait}` for groups needing a release,
including blocking shell commands. No rollback or retry safety is promised.
[`chain!`][api:libtmux.core/chain!] carries an implicit target between dependent
commands.

### [`libtmux.core/buffers!`][libtmux.core]

`(buffers! server)`

Lists captured buffer names and byte sizes.
[`show-buffer!`][api:libtmux.core/show-buffer!] reads an individual buffer.

### [`libtmux.core/capture!`][libtmux.core]

`(capture! pane)`

`(capture! pane options)`

Captures pane lines; `:from` `:history` includes scrollback.
[`await-text!`][api:libtmux.core/await-text!] waits for text;
[`libtmux.control/subscribe!`][api:libtmux.control/subscribe!] observes new
output.

### [`libtmux.core/chain!`][libtmux.core]

`(chain! server operations)`

`(chain! server operations options)`

Runs dependent argv commands with tmux's implicit target between positions.
Uses a fresh Java CommandChain. Failed/skipped outcomes are data; transport
failure throws with `:tmux/results.` Use `{:kind :wait}` for groups needing a
release, including blocking shell commands. No rollback or retry safety is
promised.
[`batch!`][api:libtmux.core/batch!] runs commands with explicit independent
targets.

### [`libtmux.core/clients!`][libtmux.core]

`(clients! server)`

Acquires captured clients.
[`detach-client!`][api:libtmux.core/detach-client!] acts on a selected client
reference.

### [`libtmux.core/close!`][libtmux.core]

`(close! server)`

Releases the supplied Server's local resources without killing tmux, except for
a
server from [`start!`][api:libtmux.core/start!], whose close stops the tmux
server it owns.
Local closure remains available after
[`libtmux.async/cancel!`][api:libtmux.async/cancel!].
[`open!`][api:libtmux.core/open!] creates another client.

### [`libtmux.core/delete-buffer!`][libtmux.core]

`(delete-buffer! server name)`

Deletes an explicit buffer.
[`buffers!`][api:libtmux.core/buffers!] lists the remaining buffers.

### [`libtmux.core/detach-client!`][libtmux.core]

`(detach-client! client)`

Detaches the explicit client.
[`clients!`][api:libtmux.core/clients!] acquires available clients;
[`switch-client!`][api:libtmux.core/switch-client!] changes their session.

### [`libtmux.core/environment!`][libtmux.core]

`(environment! value)`

`(environment! value scope)`

Returns values and explicitly removed names, preserving removal versus absence.
[`unset-environment!`][api:libtmux.core/unset-environment!] allows inheritance;
[`remove-environment!`][api:libtmux.core/remove-environment!] masks a name.

### [`libtmux.core/error-summary`][libtmux.core]

`(error-summary failure)`

Returns fixed error metadata without arguments or cause messages.
Printing the original exception and its cause chain is not sanitized.
Use this summary when handling failures from
[`new-session!`][api:libtmux.core/new-session!] or
[`batch!`][api:libtmux.core/batch!].

### [`libtmux.core/expand!`][libtmux.core]

`(expand! value format)`

Evaluates an explicit tmux format at the supplied scope.
[`capture!`][api:libtmux.core/capture!] reads terminal lines rather than
evaluating a tmux format.

### [`libtmux.core/find-session!`][libtmux.core]

`(find-session! server name)`

Finds a session by exact name; absence returns `nil`.
Use [`sessions!`][api:libtmux.core/sessions!] when the selection needs ordinary
Clojure predicates.

### [`libtmux.core/hooks!`][libtmux.core]

`(hooks! value)`

Returns hook names and their ordered command vectors.
[`set-hook!`][api:libtmux.core/set-hook!] replaces commands;
[`append-hook!`][api:libtmux.core/append-hook!] preserves their order.

### [`libtmux.core/join-pane!`][libtmux.core]

`(join-pane! pane window)`

Joins a pane to a window.
[`split-pane!`][api:libtmux.core/split-pane!] creates a new pane instead of
relocating an existing one.

### [`libtmux.core/kill!`][libtmux.core]

`(kill! value)`

Kills the explicitly supplied session, window, or pane.
[`close!`][api:libtmux.core/close!] releases a client;
[`kill-server!`][api:libtmux.core/kill-server!] ends its entire tmux daemon.

### [`libtmux.core/kill-server!`][libtmux.core]

`(kill-server! server)`

Kills the explicit tmux server; [`close!`][api:libtmux.core/close!] only
releases local resources.

### [`libtmux.core/link-window!`][libtmux.core]

`(link-window! window session)`

Links a window into a session.
[`unlink-window!`][api:libtmux.core/unlink-window!] removes a placement;
[`move-window!`][api:libtmux.core/move-window!] relocates one.

### [`libtmux.core/move-window!`][libtmux.core]

`(move-window! window session index)`

Moves a window to a session and explicit index.
Use [`snapshot!`][api:libtmux.core/snapshot!] to find the new placement after
moving.

### [`libtmux.core/new-session!`][libtmux.core]

`(new-session! server options)`

Creates a detached session and returns a fresh captured session map.
Options are `:name`, `:window-name`, `:command` (nonempty argv vector),
`:directory`
(path), and `:environment` (string keys and values). Unknown keys are rejected.
A recapture failure can occur after creation; inspect
[`error-summary`][api:libtmux.core/error-summary] before
retrying a mutation.

### [`libtmux.core/new-window!`][libtmux.core]

`(new-window! session options)`

Creates a window and returns a fresh captured window occurrence.
Takes a Session ref and `:name`, `:command` (argv), `:directory`,
`:environment`,
`:index` (nonnegative integer), and `:detached?` options.
Use [`split-pane!`][api:libtmux.core/split-pane!] to add panes within the new
window.

### [`libtmux.core/open!`][libtmux.core]

`(open! options)`

Opens local resources for an explicit `:socket-path` or `:socket-name` endpoint.
Supply exactly one endpoint. Paths accept String or java.nio.file.Path.
Optional `:binary` is a command name or path; `:config-file` is a path;
`:timeout` is a positive java.time.Duration. Unknown keys are rejected.
Returns a Server for with-open; closing it does not kill tmux.
Use [`snapshot!`][api:libtmux.core/snapshot!] to acquire state and
[`close!`][api:libtmux.core/close!] to release this client.

#### What is on here

The functions of `libtmux.core` fall into these groups:

**Connecting and closing.** [`open!`][api:libtmux.core/open!] returns a Server
for one explicit
`:socket-path` or `:socket-name`, which `with-open` closes.
[`close!`][api:libtmux.core/close!]
releases only that client and never kills tmux.
[`start!`][api:libtmux.core/start!] starts a private
tmux server instead, and closing that Server stops tmux and removes its
directory. [`libtmux.control/attach!`][api:libtmux.control/attach!] attaches a
separate control connection
to a captured session.

- Open and close: [`open!`][api:libtmux.core/open!],
[`start!`][api:libtmux.core/start!], [`close!`][api:libtmux.core/close!]
- Control: [`libtmux.control/attach!`][api:libtmux.control/attach!]

**Listing everything.** [`snapshot!`][api:libtmux.core/snapshot!] acquires every
session, window, pane
and client as one eager captured graph.
[`sessions!`][api:libtmux.core/sessions!],
[`windows!`][api:libtmux.core/windows!],
[`panes!`][api:libtmux.core/panes!] and [`clients!`][api:libtmux.core/clients!]
each take a fresh snapshot and return one
collection, and a failed acquisition throws.
[`refresh!`][api:libtmux.core/refresh!] reacquires one
reference. [`libtmux.data/windows`][api:libtmux.data/windows] and
[`libtmux.data/panes`][api:libtmux.data/panes] traverse a
capture locally without asking tmux again.

- Acquire: [`snapshot!`][api:libtmux.core/snapshot!],
[`refresh!`][api:libtmux.core/refresh!]
- One collection: [`sessions!`][api:libtmux.core/sessions!],
[`windows!`][api:libtmux.core/windows!], [`panes!`][api:libtmux.core/panes!],
[`clients!`][api:libtmux.core/clients!]
- Traverse a capture: [`libtmux.data/windows`][api:libtmux.data/windows],
[`libtmux.data/panes`][api:libtmux.data/panes],
[`libtmux.data/active-window`][api:libtmux.data/active-window],
[`libtmux.data/active-pane`][api:libtmux.data/active-pane]
- Plain EDN: [`libtmux.data/data`][api:libtmux.data/data]

**Finding one thing.** [`find-session!`][api:libtmux.core/find-session!] looks a
session up by exact name and
returns `nil` when tmux has none. For anything else, filter a capture with an
ordinary Clojure predicate or with
[`libtmux.data/matching`][api:libtmux.data/matching], which takes a
Java `FilterExpr`, then take the result with
[`libtmux.data/exactly-one`][api:libtmux.data/exactly-one],
which throws for none or several, or
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none], which
returns `nil` for none and throws for several.

- By name: [`find-session!`][api:libtmux.core/find-session!]
- Filter: [`libtmux.data/matching`][api:libtmux.data/matching],
[`libtmux.data/predicate`][api:libtmux.data/predicate]
- Take one: [`libtmux.data/exactly-one`][api:libtmux.data/exactly-one],
[`libtmux.data/one-or-none`][api:libtmux.data/one-or-none]

**Creating and removing.** [`new-session!`][api:libtmux.core/new-session!],
[`new-window!`][api:libtmux.core/new-window!] and
[`split-pane!`][api:libtmux.core/split-pane!] each create one thing from an
options map, reject unknown
keys, and return a fresh capture of it. [`kill!`][api:libtmux.core/kill!]
removes one session, window
or pane. [`kill-server!`][api:libtmux.core/kill-server!] ends the whole tmux
server, which [`close!`][api:libtmux.core/close!] does
not.

- Create: [`new-session!`][api:libtmux.core/new-session!],
[`new-window!`][api:libtmux.core/new-window!],
[`split-pane!`][api:libtmux.core/split-pane!]
- Remove: [`kill!`][api:libtmux.core/kill!],
[`kill-server!`][api:libtmux.core/kill-server!]

**Acting on objects.** [`rename!`][api:libtmux.core/rename!] renames a session
or window and [`select!`][api:libtmux.core/select!]
selects a window or pane. [`link-window!`][api:libtmux.core/link-window!],
[`unlink-window!`][api:libtmux.core/unlink-window!] and
[`move-window!`][api:libtmux.core/move-window!] change which session holds a
window, and [`join-pane!`][api:libtmux.core/join-pane!] moves
a pane into a window. [`resize!`][api:libtmux.core/resize!] sets cell dimensions
and [`apply-layout!`][api:libtmux.core/apply-layout!]
applies a tmux layout string.
[`detach-client!`][api:libtmux.core/detach-client!] and
[`switch-client!`][api:libtmux.core/switch-client!] act on
a client from [`clients!`][api:libtmux.core/clients!].

- Rename and select: [`rename!`][api:libtmux.core/rename!],
[`select!`][api:libtmux.core/select!]
- Windows and panes: [`link-window!`][api:libtmux.core/link-window!],
[`unlink-window!`][api:libtmux.core/unlink-window!],
[`move-window!`][api:libtmux.core/move-window!],
[`join-pane!`][api:libtmux.core/join-pane!]
- Size and layout: [`resize!`][api:libtmux.core/resize!],
[`apply-layout!`][api:libtmux.core/apply-layout!]
- Clients: [`detach-client!`][api:libtmux.core/detach-client!],
[`switch-client!`][api:libtmux.core/switch-client!]

**Typing into panes and reading them.**
[`send-literal!`][api:libtmux.core/send-literal!] sends text as
written and [`send-keys!`][api:libtmux.core/send-keys!] sends a vector of tmux
key names; a completed send
does not mean the application is ready. [`capture!`][api:libtmux.core/capture!]
returns a pane's lines,
with scrollback when `:from` is `:history`.
[`await-text!`][api:libtmux.core/await-text!] waits within a
positive Duration for text in a pane. [`expand!`][api:libtmux.core/expand!]
evaluates a tmux format at
the scope of the value it is given.

- Type: [`send-literal!`][api:libtmux.core/send-literal!],
[`send-keys!`][api:libtmux.core/send-keys!]
- Read: [`capture!`][api:libtmux.core/capture!],
[`await-text!`][api:libtmux.core/await-text!]
- Formats: [`expand!`][api:libtmux.core/expand!]

**Options, hooks, and environment.** [`options!`][api:libtmux.core/options!]
reads the options set
locally on a server, session, window or pane, or the effective inherited ones;
[`set-option!`][api:libtmux.core/set-option!] sets an override and
[`unset-option!`][api:libtmux.core/unset-option!] restores inheritance.
[`hooks!`][api:libtmux.core/hooks!] returns hook names with their ordered
command vectors, which
[`set-hook!`][api:libtmux.core/set-hook!] replaces,
[`append-hook!`][api:libtmux.core/append-hook!] extends and
[`unset-hook!`][api:libtmux.core/unset-hook!] removes.
[`environment!`][api:libtmux.core/environment!] returns the values and the names
marked removed;
[`set-environment!`][api:libtmux.core/set-environment!] sets a value,
[`unset-environment!`][api:libtmux.core/unset-environment!] drops the local
entry so the name is inherited, and
[`remove-environment!`][api:libtmux.core/remove-environment!] masks the name
from new processes.

- Options: [`options!`][api:libtmux.core/options!],
[`set-option!`][api:libtmux.core/set-option!],
[`unset-option!`][api:libtmux.core/unset-option!]
- Hooks: [`hooks!`][api:libtmux.core/hooks!],
[`set-hook!`][api:libtmux.core/set-hook!],
[`append-hook!`][api:libtmux.core/append-hook!],
[`unset-hook!`][api:libtmux.core/unset-hook!]
- Environment: [`environment!`][api:libtmux.core/environment!],
[`set-environment!`][api:libtmux.core/set-environment!],
[`unset-environment!`][api:libtmux.core/unset-environment!],
[`remove-environment!`][api:libtmux.core/remove-environment!]

**Buffers and channels.** [`set-buffer!`][api:libtmux.core/set-buffer!] stores
literal contents under a
name, [`show-buffer!`][api:libtmux.core/show-buffer!] reads them back and
[`delete-buffer!`][api:libtmux.core/delete-buffer!] removes the
buffer. [`buffers!`][api:libtmux.core/buffers!] lists names and byte sizes
without reading contents, and
[`paste-buffer!`][api:libtmux.core/paste-buffer!] sends one to a pane.
[`await-channel!`][api:libtmux.core/await-channel!] waits on a tmux
channel until [`signal-channel!`][api:libtmux.core/signal-channel!] releases it.

- Buffers: [`buffers!`][api:libtmux.core/buffers!],
[`set-buffer!`][api:libtmux.core/set-buffer!],
[`show-buffer!`][api:libtmux.core/show-buffer!],
[`delete-buffer!`][api:libtmux.core/delete-buffer!],
[`paste-buffer!`][api:libtmux.core/paste-buffer!]
- Channels: [`await-channel!`][api:libtmux.core/await-channel!],
[`signal-channel!`][api:libtmux.core/signal-channel!]

**Running a command.** Anything tmux can do that is not above is reachable by
running a command directly. [`raw!`][api:libtmux.core/raw!] runs one tmux argv
with no shell and
returns its exit status and streams, and
[`run-shell!`][api:libtmux.core/run-shell!] runs a shell command
on the server. [`batch!`][api:libtmux.core/batch!] runs independent argv
commands with explicit
targets and [`chain!`][api:libtmux.core/chain!] runs dependent ones, which share
tmux's implicit
target; failed or skipped commands come back as data, and a transport failure
throws. [`error-summary`][api:libtmux.core/error-summary] reduces a thrown
failure to fixed metadata, without
arguments or cause messages.

- One command: [`raw!`][api:libtmux.core/raw!],
[`run-shell!`][api:libtmux.core/run-shell!]
- Several commands: [`batch!`][api:libtmux.core/batch!],
[`chain!`][api:libtmux.core/chain!]
- Failures: [`error-summary`][api:libtmux.core/error-summary]

### [`libtmux.core/options!`][libtmux.core]

`(options! value)`

`(options! value scope)`

Reads `:local` options by default, or `:effective` inherited options.
[`set-option!`][api:libtmux.core/set-option!] sets an override;
[`unset-option!`][api:libtmux.core/unset-option!] restores inheritance.

### [`libtmux.core/panes!`][libtmux.core]

`(panes! server)`

Acquires captured pane occurrences.
Use [`capture!`][api:libtmux.core/capture!] for terminal contents or
[`libtmux.data/data`][api:libtmux.data/data] for detached EDN.

### [`libtmux.core/paste-buffer!`][libtmux.core]

`(paste-buffer! pane name)`

Pastes an explicit buffer into a pane.
[`set-buffer!`][api:libtmux.core/set-buffer!] supplies contents;
[`await-text!`][api:libtmux.core/await-text!] observes pane output.

### [`libtmux.core/raw!`][libtmux.core]

`(raw! server argv)`

Runs explicit tmux argv and returns exit status and streams; no shell.
Use [`batch!`][api:libtmux.core/batch!] for independent commands or
[`chain!`][api:libtmux.core/chain!] for dependent targets.

### [`libtmux.core/refresh!`][libtmux.core]

`(refresh! entry)`

Reacquires an explicit reference, retaining window and pane placement.
Returns `nil` if that occurrence disappeared; server failure throws.
Compare identity with
[`libtmux.data/same-entity?`][api:libtmux.data/same-entity?]; map equality
compares observations.

### [`libtmux.core/remove-environment!`][libtmux.core]

`(remove-environment! value name)`

Marks an environment name removed for new child processes.
[`unset-environment!`][api:libtmux.core/unset-environment!] removes that local
marker and allows inheritance.

### [`libtmux.core/rename!`][libtmux.core]

`(rename! value name)`

Renames a session or window and captures its current data.

### [`libtmux.core/resize!`][libtmux.core]

`(resize! value width height)`

Resizes a window or pane to positive cell dimensions.
[`apply-layout!`][api:libtmux.core/apply-layout!] arranges all panes in a
window.

### [`libtmux.core/run-shell!`][libtmux.core]

`(run-shell! server command)`

Runs an explicitly requested shell command on the server.
[`raw!`][api:libtmux.core/raw!] accepts tmux argv;
[`await-channel!`][api:libtmux.core/await-channel!] can gate an explicit
completion signal.

### [`libtmux.core/select!`][libtmux.core]

`(select! value)`

Selects the preserved window or pane occurrence.
[`refresh!`][api:libtmux.core/refresh!] acquires the resulting selection state.

### [`libtmux.core/send-keys!`][libtmux.core]

`(send-keys! pane keys)`

Sends an explicit vector of tmux key names.
Use [`send-literal!`][api:libtmux.core/send-literal!] for text and
[`await-text!`][api:libtmux.core/await-text!] for observed output.

### [`libtmux.core/send-literal!`][libtmux.core]

`(send-literal! pane text)`

Sends literal text; completion does not mean application readiness.
[`send-keys!`][api:libtmux.core/send-keys!] sends key names;
[`await-text!`][api:libtmux.core/await-text!] checks observed output.

### [`libtmux.core/sessions!`][libtmux.core]

`(sessions! server)`

Acquires captured sessions; acquisition failure throws.
[`libtmux.data/windows`][api:libtmux.data/windows] traverses each session
without another acquisition.

### [`libtmux.core/set-buffer!`][libtmux.core]

`(set-buffer! server name text)`

Stores literal buffer contents.
[`show-buffer!`][api:libtmux.core/show-buffer!] reads the text;
[`paste-buffer!`][api:libtmux.core/paste-buffer!] sends it to a pane.

### [`libtmux.core/set-environment!`][libtmux.core]

`(set-environment! value name text)`

Sets a literal environment value.
[`environment!`][api:libtmux.core/environment!] reads values and explicitly
removed names.

### [`libtmux.core/set-hook!`][libtmux.core]

`(set-hook! value event argv)`

Replaces a hook with one explicit tmux argv command.
[`append-hook!`][api:libtmux.core/append-hook!] adds a command;
[`unset-hook!`][api:libtmux.core/unset-hook!] removes the hook.

### [`libtmux.core/set-option!`][libtmux.core]

`(set-option! value name text)`

Sets a literal option value at the explicit scope.
Read it with [`options!`][api:libtmux.core/options!]; remove the override with
[`unset-option!`][api:libtmux.core/unset-option!].

### [`libtmux.core/show-buffer!`][libtmux.core]

`(show-buffer! server name)`

Reads literal buffer contents.
[`buffers!`][api:libtmux.core/buffers!] lists names and sizes without reading
their contents.

### [`libtmux.core/signal-channel!`][libtmux.core]

`(signal-channel! server name)`

Signals an explicit tmux channel.
Releases a corresponding [`await-channel!`][api:libtmux.core/await-channel!]
wait.

### [`libtmux.core/snapshot!`][libtmux.core]

`(snapshot! server)`

Acquires an eager captured graph from an explicit server.
Traverse one observation with [`libtmux.data/windows`][api:libtmux.data/windows]
and [`libtmux.data/panes`][api:libtmux.data/panes].

### [`libtmux.core/split-pane!`][libtmux.core]

`(split-pane! pane options)`

Splits a Pane ref and returns a fresh captured pane occurrence.
Options are `:command` (argv), `:directory`, `:environment`, `:direction`
(`:below`, `:above`, `:right`, `:left`), `:cells` or `:percent` (mutually
exclusive),
`:detached?`, `:empty?`, `:full-window?`, and `:zoomed?.` `:empty?` excludes
`:command.`
A single argv element is executed literally, without shell interpolation.
Use [`apply-layout!`][api:libtmux.core/apply-layout!] to arrange the resulting
window.

### [`libtmux.core/start!`][libtmux.core]

`(start!)`

`(start! options)`

Starts a private tmux server that the returned Server owns.
The server has a directory of its own, an empty config unless `:config-file`
names one,
and none of the caller's TMUX, TMUX_PANE or LIBTMUX_SOCKET*. Optional `:binary`
is a
command name or path, `:root` a directory to make it under, `:timeout` a
positive
java.time.Duration for startup and shutdown. Unknown keys are rejected.
Returns a Server for with-open; closing it stops tmux, removes the directory,
and
throws if the server still answers or a program left another one in it.
Unlike [`open!`][api:libtmux.core/open!], which owns only a client.

### [`libtmux.core/switch-client!`][libtmux.core]

`(switch-client! client session)`

Switches an explicit client to a session.
[`detach-client!`][api:libtmux.core/detach-client!] disconnects a client instead
of switching its session.

### [`libtmux.core/unlink-window!`][libtmux.core]

`(unlink-window! window)`

Unlinks the preserved window occurrence.
[`libtmux.data/same-link?`][api:libtmux.data/same-link?] distinguishes
contextual placements.

### [`libtmux.core/unset-environment!`][libtmux.core]

`(unset-environment! value name)`

Removes the local environment entry, allowing inheritance.
[`remove-environment!`][api:libtmux.core/remove-environment!] masks a name
instead of allowing inheritance.

### [`libtmux.core/unset-hook!`][libtmux.core]

`(unset-hook! value event)`

Removes an explicit hook.
[`hooks!`][api:libtmux.core/hooks!] reads the remaining hooks.

### [`libtmux.core/unset-option!`][libtmux.core]

`(unset-option! value name)`

Removes a local option override.
[`options!`][api:libtmux.core/options!] can read the resulting effective value.

### [`libtmux.core/windows!`][libtmux.core]

`(windows! server)`

Acquires captured window occurrences.
[`libtmux.data/panes`][api:libtmux.data/panes] traverses these occurrences
without another acquisition.

## [`libtmux.data`][libtmux.data]

Pure captured values from
[`libtmux.core/snapshot!`][api:libtmux.core/snapshot!].
Traverse with [`windows`][api:libtmux.data/windows] and
[`panes`][api:libtmux.data/panes], select with
[`matching`][api:libtmux.data/matching], or detach with
[`data`][api:libtmux.data/data].

### [`libtmux.data/active-pane`][libtmux.data]

`(active-pane value)`

Returns the captured active pane of a window or session, or `nil`.
[`panes`][api:libtmux.data/panes] returns all captured occurrences;
[`libtmux.core/select!`][api:libtmux.core/select!] changes selection.

### [`libtmux.data/active-window`][libtmux.data]

`(active-window session)`

Returns the captured active window of a session, or `nil`.
[`windows`][api:libtmux.data/windows] returns all captured occurrences;
[`active-pane`][api:libtmux.data/active-pane] follows the active one.

### [`libtmux.data/data`][libtmux.data]

`(data value)`

Projects captured data to EDN, removing `:tmux/ref` at every map level.
Instants become ISO-8601 strings; sequences and Java lists become vectors.
Unsupported JVM objects throw rather than producing unreadable EDN.
Detached results cannot be used by [`predicate`][api:libtmux.data/predicate];
[`entity-key`][api:libtmux.data/entity-key] remains explicit data.

### [`libtmux.data/entity-key`][libtmux.data]

`(entity-key entry)`

Returns a captured physical identity, qualified by endpoint and server PID.
PID reuse is not a stronger daemon-incarnation guarantee.
[`link-key`][api:libtmux.data/link-key] also includes placement;
[`same-entity?`][api:libtmux.data/same-entity?] compares physical identities.

### [`libtmux.data/exactly-one`][libtmux.data]

`(exactly-one values)`

Returns the only value or throws `ex-info` for zero or multiple values.
Stops requesting matches once ambiguity is known. Finding zero or one may
scan all input; chunked producers may realize more. `nil` and `false` are
values.
[`one-or-none`][api:libtmux.data/one-or-none] accepts an empty selection;
establish cardinality before effects.

### [`libtmux.data/from-capture`][libtmux.data]

`(from-capture capture)`

Converts an authenticated Java CapturedServer to eager persistent values.
Reads no live getters and issues no commands. Relations share these values;
window links and pane occurrences retain their captured order and placement.
[`libtmux.core/snapshot!`][api:libtmux.core/snapshot!] acquires this Java
capture and projects it for callers.

### [`libtmux.data/link-key`][libtmux.data]

`(link-key entry)`

Returns the physical identity and contextual window placement.
Only window and pane occurrences carry a link; missing context throws.
[`entity-key`][api:libtmux.data/entity-key] omits placement;
[`same-link?`][api:libtmux.data/same-link?] compares contextual occurrences.

### [`libtmux.data/matching`][libtmux.data]

`(matching expression)`

`(matching expression entries)`

Filters captured entries using a Java FilterExpr over their preserved refs.
One argument returns a transducer; two return a lazy sequence. Both are local
and preserve order and multiplicity. Detached maps have no query reference.
Delegates to [`predicate`][api:libtmux.data/predicate];
[`exactly-one`][api:libtmux.data/exactly-one] checks the resulting cardinality.

### [`libtmux.data/one-or-none`][libtmux.data]

`(one-or-none values)`

Returns the only value, `nil` for none, or throws for multiple values.
Stops requesting matches once ambiguity is known. Finding zero or one may
scan all input; chunked producers may realize more. `nil` and `false` are
values.
Use [`exactly-one`][api:libtmux.data/exactly-one] when zero matches must also
fail.

### [`libtmux.data/panes`][libtmux.data]

`(panes value)`

Returns captured pane occurrences in a snapshot, session, or window.
Use [`libtmux.core/panes!`][api:libtmux.core/panes!] for a new acquisition;
[`active-pane`][api:libtmux.data/active-pane] selects locally.

### [`libtmux.data/predicate`][libtmux.data]

`(predicate expression)`

Adapts a Java FilterExpr to a predicate over captured entries.
Evaluates the preserved `:tmux/ref`, including Java relation semantics;
editing fields in an entry does not change the reference being queried.
[`matching`][api:libtmux.data/matching] provides transducer and sequence
arities; [`data`][api:libtmux.data/data] removes refs.

### [`libtmux.data/same-entity?`][libtmux.data]

`(same-entity? left right)`

Compares physical entity identities without acquiring current state.
Uses [`entity-key`][api:libtmux.data/entity-key];
[`same-link?`][api:libtmux.data/same-link?] also checks placement.

### [`libtmux.data/same-link?`][libtmux.data]

`(same-link? left right)`

Compares occurrences, including the session, window ID, and index.
Uses [`link-key`][api:libtmux.data/link-key];
[`same-entity?`][api:libtmux.data/same-entity?] ignores placement.

### [`libtmux.data/windows`][libtmux.data]

`(windows value)`

Returns the captured window occurrences in a snapshot or session.
Use [`libtmux.core/windows!`][api:libtmux.core/windows!] for a new acquisition;
[`panes`][api:libtmux.data/panes] stays local.

## [`libtmux.async`][libtmux.async]

Bounded owned workers from [`runtime!`][api:libtmux.async/runtime!] and
cancellable [`submit!`][api:libtmux.async/submit!] tasks.
[`completion-stage`][api:libtmux.async/completion-stage] and
[`then!`][api:libtmux.async/then!] expose Java interop with explicit scheduling.

### [`libtmux.async/cancel!`][libtmux.async]

`(cancel! task)`

Requests cancellation. The first accepted cancellation reason wins.
[`task-state`][api:libtmux.async/task-state] distinguishes cancelling from
terminal; dereference only settles after cleanup.

### [`libtmux.async/close-runtime!`][libtmux.async]

`(close-runtime! runtime)`

Cancels owned tasks and callbacks and shuts down the runtime's executors.
Waits within `:close-timeout-ms.` Interruption-resistant work can outlive that
budget; it cannot be forcibly stopped. Pending cleanup throws `ex-info` with
`:tmux/error` `:cleanup-pending.` Safe to call again; returns `nil` after
cleanup.
[`task-state`][api:libtmux.async/task-state] keeps incomplete cleanup
observable; [`cancel!`][api:libtmux.async/cancel!] targets one task.

### [`libtmux.async/completion-stage`][libtmux.async]

`(completion-stage task)`

Returns a read-only CompletionStage completed after worker cleanup.
Completing or cancelling a derived CompletableFuture does not control the
task; use [`cancel!`][api:libtmux.async/cancel!]. Caller-attached callbacks
follow Java stage scheduling.
[`then!`][api:libtmux.async/then!] schedules bounded continuations;
[`task-state`][api:libtmux.async/task-state] exposes pending cleanup.

### [`libtmux.async/runtime!`][libtmux.async]

`(runtime!)`

`(runtime! options)`

Creates a closeable runtime owning bounded work, wait and callback lanes.
Options are `:max-running` (default 4, minimum 2), `:queue-limit` (default 64,
positive), and `:close-timeout-ms` (default 900, positive). One running slot is
reserved for waits so ordinary work can release them. Closing cancels owned
work and waits within the close budget; it never closes borrowed servers.
Submit with [`submit!`][api:libtmux.async/submit!];
[`close-runtime!`][api:libtmux.async/close-runtime!] documents incomplete
cleanup.

### [`libtmux.async/submit!`][libtmux.async]

`(submit! runtime thunk)`

`(submit! runtime options thunk)`

Submits a zero-argument function with the caller's dynamic bindings.
Options are `:kind` (`:ordinary` by default, or `:wait`) and a positive
`:timeout-ms` deadline including queue time. Returns a dereferenceable Task
or `{:status :busy :kind kind}` when admission is full. Timed deref does not
cancel a task; [`cancel!`][api:libtmux.async/cancel!] or its deadline interrupts
owned work. Effects that
wait require `:wait.` Exceptions retain dispatch certainty and partial results.

### [`libtmux.async/task-kind`][libtmux.async]

`(task-kind task)`

Returns the declared `:ordinary` or `:wait` lane without waiting for the task.
Declared by [`submit!`][api:libtmux.async/submit!];
[`task-state`][api:libtmux.async/task-state] reports execution and cleanup
progress.

### [`libtmux.async/task-state`][libtmux.async]

`(task-state task)`

Reads task progress: `:queued`, `:running`, `:cancelling`, `:cleanup-pending`,
or `:terminal.` A cancellation request is not proof cleanup has finished.
[`cancel!`][api:libtmux.async/cancel!] requests interruption;
[`completion-stage`][api:libtmux.async/completion-stage] settles after cleanup.

### [`libtmux.async/then!`][libtmux.async]

`(then! runtime task f)`

Returns a read-only CompletionStage applying `f` after successful task cleanup.
Runs on the runtime's bounded callback lane with the caller's dynamic
bindings. Full callback admission throws `ex-info` with `:tmux/error` `:busy.`
Closing the runtime cancels queued callbacks and interrupts active ones.
After [`close-runtime!`][api:libtmux.async/close-runtime!], callbacks cannot
issue later tmux calls.
[`completion-stage`][api:libtmux.async/completion-stage] exposes ordinary Java
stage scheduling instead.

## [`libtmux.control`][libtmux.control]

Explicit control replies through [`attach!`][api:libtmux.control/attach!] and
[`send!`][api:libtmux.control/send!].
[`subscribe!`][api:libtmux.control/subscribe!],
[`next!`][api:libtmux.control/next!], and
[`terminal`][api:libtmux.control/terminal] expose owned bounded output streams.
Control needs tmux 3.7 or newer; an older daemon is refused before
any client is attached, with `:tmux/error` `:tmux/unsupported-feature`.

### [`libtmux.control/attach!`][libtmux.control]

`(attach! session)`

`(attach! session timeout)`

Attaches an owned control client to an explicit captured Session reference.
Close releases this client, never its server. Use a dedicated connection
when cancellation must not affect other requests and subscriptions.
[`send!`][api:libtmux.control/send!] sends protocol argv;
[`subscribe!`][api:libtmux.control/subscribe!] observes output on this
connection.

### [`libtmux.control/close!`][libtmux.control]

`(close! resource)`

Closes a connection or subscription. Closing a subscription leaves its
borrowed connection open; closing a connection affects all its subscribers.
Local closure remains available after
[`libtmux.async/cancel!`][api:libtmux.async/cancel!].
[`terminal`][api:libtmux.control/terminal] inspects a drained subscription;
[`attach!`][api:libtmux.control/attach!] opens another connection.

### [`libtmux.control/next!`][libtmux.control]

`(next! stream)`

`(next! stream timeout)`

Reads one event, gap, timeout, or terminal map; never uses `nil` for
termination.
A `:gap` names the events dropped since the previous read and precedes the
events that remain. The optional nonnegative Duration includes waiting for
another reader. Sequence numbers order the events of this stream. Close wakes
a reader. Termination drains queued deliveries before the final outcome;
intentionally closing this subscription discards its pending deliveries.
[`subscribe!`][api:libtmux.control/subscribe!] sets bounds;
[`close!`][api:libtmux.control/close!] wakes a blocked reader.

### [`libtmux.control/send!`][libtmux.control]

`(send! connection argv)`

`(send! connection argv timeout)`

Sends argv and returns protocol status, lines, and unverified completion.
A complete reply does not prove deferred work finished. Cancelling an active
send can end this connection and its other requests and subscriptions.
[`libtmux.core/raw!`][api:libtmux.core/raw!] uses process execution;
[`subscribe!`][api:libtmux.control/subscribe!] observes unsolicited output.

### [`libtmux.control/subscribe!`][libtmux.control]

`(subscribe! connection)`

`(subscribe! connection options)`

Creates a closeable output subscription borrowing its connection.
`:encoding` is `:bytes` (caller-owned byte arrays) or `:utf-8` (incremental text
per pane, reset after this subscription's gaps; malformed bytes use \xHH).
`:decoder-capacity` bounds partial-pane state (default 1024); an eviction is
reported with `:decoder/reset` `:capacity` and a cumulative `:decoder/resets.`
`:capacity` bounds queued events; `:max-bytes` bounds their unescaped payload
(default 16 MiB).
Oldest events are dropped until both bounds fit; an oversized event drops
the queued prefix and itself. The next read is a `:gap` with byte/event loss.
Readers compete; create separate subscriptions for independent fan-out.
[`next!`][api:libtmux.control/next!] reads deliveries;
[`terminal`][api:libtmux.control/terminal] reports the drained end;
[`close!`][api:libtmux.control/close!] stops locally.

### [`libtmux.control/terminal`][libtmux.control]

`(terminal stream)`

Returns the single recorded stream outcome, or `nil` until a reader drains it.
`:reason` is `:closed`, `:completed`, `:control-exit`, `:transport-failure`,
`:protocol-failure`, or `:unknown.` A control exit does not prove daemon death;
`:cause` preserves the original exception and may contain sensitive content.
Text streams include `:trailing-text` keyed by pane ID for
incomplete final bytes rendered as \xHH.
[`next!`][api:libtmux.control/next!] records this outcome after buffered
deliveries have drained.

[libtmux.core]:
  ../../libtmux-clojure/src/main/clojure/libtmux/core.clj
[libtmux.data]:
  ../../libtmux-clojure/src/main/clojure/libtmux/data.clj
[libtmux.async]:
  ../../libtmux-clojure/src/main/clojure/libtmux/async.clj
[libtmux.control]:
  ../../libtmux-clojure/src/main/clojure/libtmux/control.clj
[api:libtmux.async/cancel!]:
  #libtmuxasynccancel
[api:libtmux.async/close-runtime!]:
  #libtmuxasyncclose-runtime
[api:libtmux.async/completion-stage]:
  #libtmuxasynccompletion-stage
[api:libtmux.async/runtime!]:
  #libtmuxasyncruntime
[api:libtmux.async/submit!]:
  #libtmuxasyncsubmit
[api:libtmux.async/task-state]:
  #libtmuxasynctask-state
[api:libtmux.async/then!]:
  #libtmuxasyncthen
[api:libtmux.control/attach!]:
  #libtmuxcontrolattach
[api:libtmux.control/close!]:
  #libtmuxcontrolclose
[api:libtmux.control/next!]:
  #libtmuxcontrolnext
[api:libtmux.control/send!]:
  #libtmuxcontrolsend
[api:libtmux.control/subscribe!]:
  #libtmuxcontrolsubscribe
[api:libtmux.control/terminal]:
  #libtmuxcontrolterminal
[api:libtmux.core/append-hook!]:
  #libtmuxcoreappend-hook
[api:libtmux.core/apply-layout!]:
  #libtmuxcoreapply-layout
[api:libtmux.core/await-channel!]:
  #libtmuxcoreawait-channel
[api:libtmux.core/await-text!]:
  #libtmuxcoreawait-text
[api:libtmux.core/batch!]:
  #libtmuxcorebatch
[api:libtmux.core/buffers!]:
  #libtmuxcorebuffers
[api:libtmux.core/capture!]:
  #libtmuxcorecapture
[api:libtmux.core/chain!]:
  #libtmuxcorechain
[api:libtmux.core/clients!]:
  #libtmuxcoreclients
[api:libtmux.core/close!]:
  #libtmuxcoreclose
[api:libtmux.core/delete-buffer!]:
  #libtmuxcoredelete-buffer
[api:libtmux.core/detach-client!]:
  #libtmuxcoredetach-client
[api:libtmux.core/environment!]:
  #libtmuxcoreenvironment
[api:libtmux.core/error-summary]:
  #libtmuxcoreerror-summary
[api:libtmux.core/expand!]:
  #libtmuxcoreexpand
[api:libtmux.core/find-session!]:
  #libtmuxcorefind-session
[api:libtmux.core/hooks!]:
  #libtmuxcorehooks
[api:libtmux.core/join-pane!]:
  #libtmuxcorejoin-pane
[api:libtmux.core/kill!]:
  #libtmuxcorekill
[api:libtmux.core/kill-server!]:
  #libtmuxcorekill-server
[api:libtmux.core/link-window!]:
  #libtmuxcorelink-window
[api:libtmux.core/move-window!]:
  #libtmuxcoremove-window
[api:libtmux.core/new-session!]:
  #libtmuxcorenew-session
[api:libtmux.core/new-window!]:
  #libtmuxcorenew-window
[api:libtmux.core/open!]:
  #libtmuxcoreopen
[api:libtmux.core/options!]:
  #libtmuxcoreoptions
[api:libtmux.core/panes!]:
  #libtmuxcorepanes
[api:libtmux.core/paste-buffer!]:
  #libtmuxcorepaste-buffer
[api:libtmux.core/raw!]:
  #libtmuxcoreraw
[api:libtmux.core/refresh!]:
  #libtmuxcorerefresh
[api:libtmux.core/remove-environment!]:
  #libtmuxcoreremove-environment
[api:libtmux.core/rename!]:
  #libtmuxcorerename
[api:libtmux.core/resize!]:
  #libtmuxcoreresize
[api:libtmux.core/run-shell!]:
  #libtmuxcorerun-shell
[api:libtmux.core/select!]:
  #libtmuxcoreselect
[api:libtmux.core/send-keys!]:
  #libtmuxcoresend-keys
[api:libtmux.core/send-literal!]:
  #libtmuxcoresend-literal
[api:libtmux.core/sessions!]:
  #libtmuxcoresessions
[api:libtmux.core/set-buffer!]:
  #libtmuxcoreset-buffer
[api:libtmux.core/set-environment!]:
  #libtmuxcoreset-environment
[api:libtmux.core/set-hook!]:
  #libtmuxcoreset-hook
[api:libtmux.core/set-option!]:
  #libtmuxcoreset-option
[api:libtmux.core/show-buffer!]:
  #libtmuxcoreshow-buffer
[api:libtmux.core/signal-channel!]:
  #libtmuxcoresignal-channel
[api:libtmux.core/snapshot!]:
  #libtmuxcoresnapshot
[api:libtmux.core/split-pane!]:
  #libtmuxcoresplit-pane
[api:libtmux.core/start!]:
  #libtmuxcorestart
[api:libtmux.core/switch-client!]:
  #libtmuxcoreswitch-client
[api:libtmux.core/unlink-window!]:
  #libtmuxcoreunlink-window
[api:libtmux.core/unset-environment!]:
  #libtmuxcoreunset-environment
[api:libtmux.core/unset-hook!]:
  #libtmuxcoreunset-hook
[api:libtmux.core/unset-option!]:
  #libtmuxcoreunset-option
[api:libtmux.core/windows!]:
  #libtmuxcorewindows
[api:libtmux.data]:
  #libtmuxdata
[api:libtmux.data/active-pane]:
  #libtmuxdataactive-pane
[api:libtmux.data/active-window]:
  #libtmuxdataactive-window
[api:libtmux.data/data]:
  #libtmuxdatadata
[api:libtmux.data/entity-key]:
  #libtmuxdataentity-key
[api:libtmux.data/exactly-one]:
  #libtmuxdataexactly-one
[api:libtmux.data/link-key]:
  #libtmuxdatalink-key
[api:libtmux.data/matching]:
  #libtmuxdatamatching
[api:libtmux.data/one-or-none]:
  #libtmuxdataone-or-none
[api:libtmux.data/panes]:
  #libtmuxdatapanes
[api:libtmux.data/predicate]:
  #libtmuxdatapredicate
[api:libtmux.data/same-entity?]:
  #libtmuxdatasame-entity
[api:libtmux.data/same-link?]:
  #libtmuxdatasame-link
[api:libtmux.data/windows]:
  #libtmuxdatawindows
