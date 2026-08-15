# Where an execution mode would plug in

## Verdict

The seam is one method, and the entity layer is already mode-agnostic. Making
control mode, batching, chaining and virtual threads switchable is a smaller
change than it looks: nothing above `Server.run` depends on anything the carriers
disagree about.

## One chokepoint

Every command reaches tmux through `Server.cmd(List, Duration)`, which builds a
`CommandRequest` and hands it to the transport. `Server.run` is the strict
wrapper over it. Nothing else touches the transport.

`Batch` already proves the seam works: it takes its dispatch as a
`Function<List<String>, CommandResult>` rather than reaching for the server, so a
different carrier is a different function.

## The handles do not care

Counted across `Pane`, `Window`, `Session`, `Client`, `Options`, `Hooks` and
`Buffers` — every public handle in the library:

| what the entity layer reads off a result | uses |
| ---------------------------------------- | ---- |
| `stdout()`                               | 13   |
| `succeeded()`                            | 4    |
| `exitCode()`                             | 0    |
| `stderr()`                               | 0    |

No public method on any handle returns a `CommandResult`. Only `Server.cmd` and
`Server.run` expose one at all.

So the whole traversal, options, hooks and mutation surface already depends on
exactly two things: did it work, and what did it print.

## Which is what both carriers have

| type                             | outcome       | lines      |
| -------------------------------- | ------------- | ---------- |
| `CommandResult` (process)        | `exitCode`    | `stdout` + `stderr` |
| `ControlReply` (control mode)    | `outcome`     | `lines`    |

Both answer `succeeded()`. The disagreement is real but narrow: a process has an
exit status and two streams, a control-mode reply has neither. Control mode has
no exit code to invent and no stream split to report.

Since no handle reads `exitCode()` or `stderr()`, that disagreement never reaches
the API a user holds. It only reaches `Server.cmd`, which is the raw escape hatch
and the one place a caller has asked for process detail on purpose.

## What this decides, and what it leaves open

Decided: switching carriers cannot change what a handle call means, because the
handles already read only the intersection of what the carriers provide. That is
a property of the current design rather than something to be built.

Open, and the next bakeoff:

- What `Server.cmd` returns under a non-process carrier — keep it
  process-shaped and unavailable elsewhere, widen it to a common type, or make
  it a sealed choice the caller switches on.
- Where the mode is chosen: on `ServerConfig`, on a derived `Server` sharing the
  transport the way `Server.using` already allows, or a scoped block.
- Whether a per-call override is worth its ambiguity, and what the precedence
  reads like when it is.

## Not covered

- Whether the control carrier can honour a per-call timeout the way the process
  carrier does.
- What batching means when the underlying carrier is already one connection.
- Whether a virtual-thread carrier is a carrier at all, or just how a caller
  chooses to invoke the existing one.
