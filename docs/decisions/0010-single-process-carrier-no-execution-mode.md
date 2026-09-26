# 0010. One process carrier; no caller-selectable execution mode

Status: Accepted

## Context

A caller-selectable mode switch — direct, batched, chained, control, and a
virtual-thread variant — was explored and built far enough to see what its
shape would mean. Counted across every public handle in the library
(`Pane`, `Window`, `Session`, `Client`, `Options`, `Hooks`, `Buffers`), the
entity layer reads only `stdout()` and `succeeded()` off a command result;
`exitCode()` and `stderr()` are read nowhere above `Server.cmd`, the raw
escape hatch. So switching which carrier answered a request cannot change
what any handle call means — that is a property of the design, not something
to preserve by convention.

The five proposed modes were not five of anything: `direct` and `control` are
carriers; `batched` and `chained` are about how many commands travel together
or how a command names its target, and both compose over either carrier
already; a `virtual` mode would only have toggled something already true,
since the blocking API is already safe to call from a virtual thread (see
[0002](0002-blocking-process-transport.md)). A per-call carrier override was
also rejected: with no handle able to observe which carrier answered it, an
override would be a knob wired to nothing that a reader would reasonably
assume mattered. The one place a carrier genuinely matters — a command group,
which control mode frames as several reply blocks instead of one — routes
itself: `ControlTransport` sends a group over a process carrier without being
asked (see [0009](0009-command-groups-are-transport-agnostic.md)), because
that is a correctness rule the library can enforce, not a preference a caller
should be trusted to remember.

A persistent, generally addressable control-mode transport was separately
investigated and rejected; see
[0018](0018-control-backed-transport-rejected.md).

## Decision

Expose one process-backed transport for ordinary use. Batching (`batch()`) and
chaining (`chain()`) are call shapes over that transport, not alternative
carriers, and there is no per-call or per-server carrier switch. A caller who
must pin a specific transport uses `Server.using(config, transport)` directly.

## Consequences

Adding a genuinely observable carrier — one whose result a handle *can* read
something from that `stdout()`/`succeeded()` cannot carry — is the condition
that would reopen a mode switch; nothing today meets it. Batching and chaining
performance is measured and compared directly, not gated behind a mode
selector; see
[the operation costs benchmark](../benchmarks/operation-costs.md).
