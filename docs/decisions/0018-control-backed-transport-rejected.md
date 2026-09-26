# 0018. A persistent control-backed transport was not built

Status: Accepted

## Context

A `TmuxTransport` backed by one persistent control client, forwarding the same
`CommandRequest`s the process transport runs today, was investigated and
rejected: it returns wrong results for nearly every typed operation, not
merely slower ones, because of two control-mode framing rules confirmed live
against a running server.

**The identity fence returns nothing.** Every fenced read or mutation sends
`if-shell -F condition command stale`. Over the process transport this works
because one client process blocks until its whole command queue — including
the guarded branch — has drained, and everything printed reaches that
process's stdout undifferentiated. Over a persistent control client,
`if-shell`'s chosen branch is not run inline: tmux queues it and answers it as
its own separate reply block, after `if-shell`'s own empty acknowledgement. A
control-backed transport built on the existing reply demultiplexer would see
only the fence's empty reply and nothing else.

**A batch keeps only its first command.** `Batch`, `CommandChain`, the
four-listing hydration capture (see
[0003](0003-hierarchy-hydration-per-entity-listings.md)), and
`Server.runTogether` all send several commands as one semicolon-joined line,
expecting one combined reply. Over a persistent control client, each command
in that line gets its own separate reply block. The existing demultiplexer
assumes one reply block per request line, so fed a batch it would keep only
the first command's block and silently truncate every multi-command read this
library performs, `Server.snapshot()` included.

A blocking command such as `wait-for` with no flag or `run-shell` without
`-b` also holds the one FIFO reply lane a control connection gives every
caller, starving ordinary requests on the same connection until something else
signals the wait from a separate connection. The process transport avoids this
by reserving separate admission capacity for waiting versus ordinary traffic;
reproducing that split for a pool of persistent control connections needs
permanently dedicating some connections to waits, since a request already in
flight cannot be preempted.

Fixing this is not a narrowly scoped transport: the fencing layer
(`IncarnationFence`, `Server`) would have to become transport-aware, skipping
`if-shell` wrapping when a persistent connection already guarantees identity
continuity, and separately reassembling however many reply blocks a batch —
`if-shell` included — actually produces. That is a redesign of the fencing and
batching mechanism itself.

## Decision

Do not build a control-backed general-purpose transport. `ControlClient` and
`ControlTransport` remain in use for what they already do well — routing a
detected command group and serving `ServerMirror` subscriptions (see
[0008](0008-control-mode-framing-and-quoting.md),
[0009](0009-command-groups-are-transport-agnostic.md), and
[0014](0014-watch-a-server-with-refresh-client.md)) — not as a drop-in
replacement for the process transport.

## Consequences

A future persistent-connection transport is new engineering against a
redesigned fencing and batching contract, not a wrapper over the existing
`ControlClient`. The measurements behind this decision are summarized in
[the operation costs benchmark](../benchmarks/operation-costs.md).
