# 0011. A wait reports why it ended, not just whether it did

Status: Accepted

## Context

tmux's `wait-for` misleads a caller in two independent, silent ways. Killing
the server while a client waits on a channel exits `0` — exactly as a real
signal does — so a caller that treats success as "the thing I was waiting for
happened" proceeds against a server that is gone. Separately, signalling a
channel nobody is waiting on is remembered by the server and satisfies the
next wait whenever that comes, whether that is a later test, a later run, or a
different program sharing the server; a wait can therefore report success
having waited for nothing that happened while it was running.

A thin wrapper over `wait-for` would have compiled, passed a happy-path test,
and been wrong in both directions under exactly the conditions a
synchronization primitive exists for — the traps are visible only by killing a
server mid-wait and by signalling a channel nobody is listening to.

## Decision

Report a wait's outcome as `WakeReason.SIGNALLED`, `TIMED_OUT`, or
`SERVER_GONE`, checking the server's liveness after the wait rather than
trusting its exit status. When the server is gone, report `SERVER_GONE`
whether or not a signal also arrived, because nothing the wait was guarding
can be relied on either way. Offer `Channel.drain()` to consume and report a
pending signal before a wait begins, so a caller can start from a known state
rather than risk an old signal answering a new wait.

## Consequences

A caller cannot mistake a dead server for a delivered signal, and cannot
mistake a stale signal for a fresh one, without deliberately skipping
`drain()`. `WakeReason` is a public outcome type, not a boolean, so a
`Channel` consumer must handle all three cases explicitly.
