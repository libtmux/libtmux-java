# A persistent control transport cannot replay the identity fence or a batch

## Verdict

Not built. A `TmuxTransport` backed by one persistent control client, forwarding
the same `CommandRequest`s `ProcessTransport` runs today, returns wrong results
for nearly every typed operation — not merely slower ones. Two of tmux's own
control-mode framing rules make that so, both confirmed live rather than read
off documentation, and neither is a defect in this library's control-mode code:
it is what the protocol does.

## The identity fence returns nothing

Every fenced read and mutation (`IncarnationFence.guarded`, which
`Server.cmd(ServerSnapshot, …)`, `Server.run(ServerSnapshot, …)`,
`SnapshotCapture`, and `Batch` all route through) sends
`if-shell -F condition command stale`. Over `ProcessTransport` this works
because one client process blocks until its whole command queue, guarded
branch included, has drained, and everything printed reaches that one
process's stdout undifferentiated.

Over a persistent control client, `if-shell`'s chosen branch is not run inline:
tmux queues it and answers it as its own reply block, after `if-shell`'s own
(empty) acknowledgement. Sent to a client attached to session `other`,
condition true:

```console
$ if-shell -F '#{==:#{session_name},other}' \
    'display-message -p fenced-ok' \
    'display-message -p fenced-stale'
```

```
%begin 1790381072 362 1
%end 1790381072 362 1        <- if-shell's own reply: empty
%begin 1790381072 363 1
fenced-ok
%end 1790381072 363 1        <- the guarded command's own output, a later block
```

`ControlWriter` (`libtmux/src/main/java/io/github/libtmux/control/`)
already handles exactly this shape correctly for what it is designed to do:
a request line is followed by a `display-message -p <marker>`, and any block
arriving before that marker's own echo is a deferred tail of the
*already-answered* request, so it is discarded rather than misattributed to
whichever caller sends the next command. That is correct for `if-shell`'s
queued branch when the caller only wanted the fence *itself* answered — but
every caller here wants the guarded command's own output, and the existing
protocol has no way to fold it back into `if-shell`'s reply. A
`ControlBackedTransport` built on it would see `if-shell`'s empty reply, and
nothing else.

## A batch keeps only its first command

`Batch`, `CommandChain`, `SnapshotCapture`'s four-listing capture, and
`Server.runTogether` all send several commands as one semicolon-joined line
(`CommandStrings.group`), expecting one process's stdout back with one
command's output per section. Over a persistent control client each command
in that line gets its **own** separate reply block, not one combined block:

```console
$ list-sessions ; display-message -p "second-cmd-output"
```

```
%begin … 1
main: 1 windows …
other: 1 windows … (attached)
%end … 1                      <- list-sessions's own block
%begin … 1
second-cmd-output
%end … 1                      <- display-message's own, separate block
```

`ControlWriter`'s demultiplexer assumes one reply block per request line. Fed
a batch, it would keep the first command's block as *the* reply and discard
every block after it as a deferred tail — silently truncating
`Server.snapshot()` (both if-shell-guarded and a four-command batch) to
whatever the first listing returned, or to nothing once the fence's own
empty block is counted first.

## A blocking command starves every other request on the same connection

`wait-for` with no flag (the blocking form `Channel`/`Server.awaitChannel`
use) and `run-shell` without `-b` do not merely answer late: they hold the one
FIFO lane a control client's `ControlWriter` gives every caller. Sent in this
order, on connection A, with nobody having signalled `chan-x` yet:

```console
$ wait-for chan-x
$ display-message -p "ordinary-while-waiting"
```

the `display-message` got no reply on A for over a second. It answered only
once a second, independent connection B sent:

```console
$ wait-for -S chan-x
```

`ProcessTransport` keeps a wait from starving ordinary work by reserving
separate admission capacity for it (`executeWaiting`, one process free for
release/observe traffic even when every other slot is a waiter). A control
client has no equivalent: one FIFO, one lane. A pool of clients sized to
`admissionBound()` only bounds the blast radius to one in `N` concurrent
callers landing behind a stuck wait, not eliminate it, and reproducing
`ProcessTransport`'s waiting/ordinary split for a pool of persistent
connections — permanently dedicating some clients to waits, since a request
already in flight cannot be preempted — is new engineering, not a
transport wrapping `ControlClient`.

## What already holds

An untargeted command sent over an attached control client resolves against
that client's own attached session, deterministically — not against tmux's
"most recently used" fallback a fresh, unattached process would get. Sent to
a client attached to `other`, with `main` touched more recently:

```console
$ display-message -p "session=#{session_name}"
```
```
%begin … 1
session=other
%end … 1
```

This is already safe for the whole typed surface: every command that acts on
a specific session, window, or pane already carries an explicit `-t`
(confirmed across `Session.java`, `Window.java`, `Pane.java`); the only
untargeted commands the library sends are server-wide reads
(`display-message -p "#{pid}"`, `list-sessions`) whose answer does not depend
on which session is current.

A failed command's reply block also carries the error text directly, over
`%error` rather than a separate channel:

```console
$ bogus-command-xyz
```
```
%begin … 1
parse error: unknown command: bogus-command-xyz
%error … 1
```

## Why the fix is not a transport

A persistent, identity-verified control connection does not actually need
`if-shell`'s per-command re-verification: tmux tears the connection down
(`ControlEndedException`) if the server it is attached to exits, rather than
silently handing it to a replacement bound to the same socket path, so
"is this still the right incarnation" is already answered by "is this
connection still open." Exploiting that means the fencing layer
(`IncarnationFence`, `Server`) becoming transport-aware — skip `if-shell`
wrapping when the transport already guarantees incarnation continuity — and
separately reassembling a batch's several reply blocks, complicated by
`if-shell` contributing an unpredictable extra one whenever it appears inside
one. That is a redesign of the fencing and batching mechanism itself, not a
narrowly scoped opt-in transport.

## Commands

```console
$ mkdir -p /tmp/libtmux-java-dev/control-probe
$ tmux -S /tmp/libtmux-java-dev/control-probe/sock -u new-session -d -s main
$ tmux -S /tmp/libtmux-java-dev/control-probe/sock -u new-session -d -s other
$ tmux -S /tmp/libtmux-java-dev/control-probe/sock -u -C attach-session -t other
```

Then the lines shown above, typed at the control connection, one at a time.

## Not covered

- Measured against 3.7d only, the newest available here, ahead of the
  project's own supported ceiling of 3.7c. `if-shell` queuing its branch as a
  separate command-queue item, and a semicolon-joined line answering as
  several blocks, are load-bearing parts of tmux's control-mode design that
  predate 3.2a; the version matrix is what would confirm that rather than
  this note.
- Whether a from-scratch protocol — tracking exactly how many reply blocks a
  given batch should produce, `if-shell` included, and reassembling them
  client-side — could be made correct was not attempted. It would need the
  fencing redesign described above first, and is future work rather than
  something this spike ruled out.
