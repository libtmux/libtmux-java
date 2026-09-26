# 0013. A server's identity is its pid and its start time together

Status: Accepted

## Context

A pid alone is reusable. A tmux server restarted inside a container, where it
is often one of the first processes started, is commonly handed back its old
pid, and a handle captured before the restart would pass a pid-only identity
check against the new, unrelated server. Measured across every released tmux
lane by starting a server, reading `#{pid}` and `#{start_time}`, killing it,
waiting over a second, and starting another on the same socket: `start_time`
changed on every restart except once, on tmux 3.7c, where the wall clock's own
drift put both servers in the same reported second. That exception is exactly
why the two values are compared together rather than either alone — a
collision needs the same pid *and* the same second, which is a materially
smaller risk than either fact by itself.

## Decision

Fence a captured handle, a snapshot, and a control attach against the
server's pid and `#{start_time}` together (`IncarnationFence`,
`ServerIdentity`), not against pid alone and not against tmux's version. A
mismatch on either value means the handle no longer names the server it was
taken from.

## Consequences

An operation against a stale handle fails with a typed exception rather than
silently acting on a same-pid server that is not the one the caller meant.
Every fenced read or mutation (`Server.cmd(ServerSnapshot, …)`,
`Server.run(ServerSnapshot, …)`, `SnapshotCapture`, `Batch`) carries this check
inline as part of the same tmux invocation, so the fence and the action it
guards cannot be torn apart by a race.
