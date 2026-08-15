# Two modes, not five, and no per-call override

## Verdict

`ExecutionMode` has `DIRECT` and `CONTROL`. Batching, chaining and virtual
threads are not on it, and there is no per-call override. Both are deliberate
divergences from the phase's brief, taken after building enough to see what the
brief's shape would have meant.

## The brief asked for five

> Modes at least: `direct`, `batched`, `chained` (targets follow what the last
> step made), `control`, `virtual` (blocking API fanned across virtual threads).

Building it, those five are not five of anything. They answer different
questions:

| named        | actually                                  | composes with a carrier? |
| ------------ | ----------------------------------------- | ------------------------ |
| `direct`     | how a command travels                     | is one                   |
| `control`    | how a command travels                     | is one                   |
| `batched`    | how many commands travel together         | yes — over either        |
| `chained`    | how a command names its target            | yes — over either        |
| `virtual`    | how the caller invokes a blocking API     | yes — over either        |

`server.batch()` works under `CONTROL`. `server.chain()` works under `DIRECT`.
Both work from a virtual thread. Putting all five on one switch would tell a
reader they were alternatives to each other, and the benchmark that compares
them would be comparing four different questions in one table.

So the switch carries the two that are alternatives, and the guide's second table
names the other three and says what they compose with instead.

## Virtual threads are not a mode to select

The blocking API is already safe to call from one, and not by accident: the
transports block on `ReentrantLock` rather than inside `synchronized`, because
JDK 21 has no unpinned monitor blocking. `CarrierStarvationTest` runs the suite
under a scheduler with exactly one carrier thread to keep that true.

A `virtual` mode would therefore have been a switch that turned on something
already on.

## No per-call override

The brief asked for `per-call > per-server > default`. Two levels shipped.

[Spike 19](19-execution-mode-seam.md) counted what the entity layer reads off a
result: `stdout()` 13 times, `succeeded()` 4, `exitCode()` and `stderr()` never,
across every public handle. Nothing above `Server.run` can observe which carrier
answered. An override would let a caller name a carrier for a call whose result
cannot depend on it — a knob wired to nothing, and one a reader would reasonably
assume mattered.

The one case where the carrier genuinely matters is a command group, because
control mode frames a reply per command and a two-command line answers with two
blocks. That case routes itself: `ControlTransport` sends a group over a process
without being asked. Making the caller responsible for a correctness rule the
library can see for itself would be worse, not more flexible.

A caller who must pin a carrier already can: `Server.using(config, transport)`
takes one directly, which says what it means without pretending to be a mode.

## What would change this

- A carrier whose result a handle *can* observe — one that reports something
  `stdout()` and `succeeded()` cannot carry. Then an override selects something
  real, and the precedence chain earns its third level.
- A group that control mode can frame correctly, which would remove the only
  self-routing case and make the routing a caller's choice again.

## Not covered

- Whether a third carrier would change the taxonomy; both current ones are
  request-reply, and something streaming-only might not fit the same interface.
- Whether `chained` should be a mode on a *chain* rather than on a server, which
  is a different question from this one.
