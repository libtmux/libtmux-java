# docs

**An index of this directory: task-oriented guides first, then the dated record
of how the library got here.**

## Guides

Task-oriented. Start here.

- [Getting started](guide/getting-started.md) — the first program, compiled and
  run against a real tmux server.
- [Filtering](guide/filtering.md) — an expression is a value: printable,
  storable, and usable as a predicate.
- [Options and hooks](guide/options-and-hooks.md) — reading and setting options
  and hooks at each of tmux's four scopes.
- [Batching and chaining](guide/batching-and-chaining.md) — putting several
  independent or dependent commands into one tmux invocation.
- [Snapshots and handles](guide/snapshots-and-handles.md) — what a capture
  freezes, and when to call `refresh()`.
- [Streaming](guide/streaming.md) — watching pane output as it happens, cheapest
  first.
- [Failures, telemetry, and pane input](guide/operations.md) — driving tmux from
  a service: retries, logging, and pane input.
- [Threads, cancellation, and what runs at once](guide/concurrency.md) — the
  blocking and thread-safety contract, and what cancellation leaves behind.
- [Driving tmux from a model](guide/mcp.md) — the `libtmux-mcp` server, for any
  MCP client.
- [Testing with real tmux](guide/testing.md) — `libtmux-junit5`: one tmux server
  per test, always cleaned up.
- [Kotlin](guide/kotlin.md) — why the core is already null-safe from Kotlin, and
  what `libtmux-kotlin` adds.
- [Scala](guide/scala.md) — the direct-Java path and the separate Scala facade,
  with their runnable examples.

## Benchmarks

- [Operation costs](benchmarks/operations.md) — measured wall-clock and
  tmux-process cost for one-at-a-time, batched, and chained calls; regenerated
  by `./gradlew operationBenchmark`.

## Spikes

Design records: what was measured against a real tmux before a decision, dated
to when it was measured and kept even after the decision it fed became
obsolete. `docs/plans/2026-08-09-disposable-spikes.md` records the process that
produced them.

- [00 Protocol](spikes/00-protocol.md) — the ground rules: what a spike may
  touch, stage, and claim.
- [01 Build and coordinates](spikes/01-build-and-coordinates.md) — the included
  `build-logic` convention build, and the coordinates it publishes.
- [02 Transport](spikes/02-transport.md) — admission-bounded prestarted platform
  pumps, chosen over virtual-thread drains.
- [03 Hydration](spikes/03-hydration.md) — one server-wide listing per entity
  kind, not a walk per object.
- [04 Query metamodel](spikes/04-query-metamodel.md) — superseded in part; the
  note at the top names the current handle design.
- [05 JUnit lifecycle](spikes/05-junit-lifecycle.md) — fixtures held in the
  extension store, released from a lifecycle callback.
- [06 Integrated synthesis](spikes/06-integrated-synthesis.md) — the frozen
  contracts from five bakeoffs, built as one vertical slice.
- [07 Row framing](spikes/07-row-framing.md) — splitting listing rows on a
  separator generated per process, not a fixed one.
- [08 Control mode](spikes/08-control-mode.md) — one command per control-mode
  line, attributed by tmux's own reply framing.
- [09 break-pane on 3.7](spikes/09-break-pane-3.7.md) — always naming the window
  `break-pane` creates, and the rename 3.7 needs.
- [10 wait-for](spikes/10-wait-for.md) — reporting why a wait ended, not just
  whether it succeeded.
- [11 split-window flags](spikes/11-split-window-flags.md) — one size flag,
  `-l`, and never `-p`, across the supported range.
- [12 Socket path reuse](spikes/12-socket-path-reuse.md) — never starting a
  server on a socket path that has held one before.
- [13 Creation call shape](spikes/13-creation-call-shape.md) — a builder-built
  spec, applied by three overloads.
- [14 new-window and new-session](spikes/14-new-window-and-new-session.md) — the
  two behaviors that differ by release, and the one flag left unexposed.
- [15 Pane modes](spikes/15-pane-modes.md) — every pane mode works on a server
  nobody is attached to.
- [16 run-shell output](spikes/16-run-shell-output.md) — a version rule for a
  gap that opens and closes within the supported range.
- [17 find-window](spikes/17-find-window.md) — why `find-window` is exposed as a
  chooser, not a search.
- [18 select-layout on 3.3a](spikes/18-select-layout-kills-3.3a.md) — why an
  unparseable layout string is never handed to `select-layout`.
- [19 Execution mode seam](spikes/19-execution-mode-seam.md) — the one seam an
  execution mode needs, in an already mode-agnostic entity layer.
- [20 Mode taxonomy](spikes/20-mode-taxonomy.md) — `ExecutionMode` is `DIRECT`
  or `CONTROL`, not five modes, chosen once per server.
- [21 Command group boundaries](spikes/21-command-group-boundaries.md) — the
  first real disagreement between the transport carriers under test.
- [22 Abandoned servers](spikes/22-abandoned-servers.md) — a shutdown hook plus
  a sweep that reads ownership from the process table.
- [23 Control subscriptions](spikes/23-control-subscriptions.md) — watching a
  server with `refresh-client -B` instead of polling.
- [24 MCP concurrency](spikes/24-mcp-concurrency.md) — serving with
  `McpServer.sync`, which runs handlers off the connection thread.
- [25 run-command framing](spikes/25-run-command-framing.md) — framing a command
  with a random nonce and cutting on whole-line equality.
- [26 Agent behaviour](spikes/26-agent-behaviour.md) — how a model given no
  briefing on this API actually ends up using it.
- [27 Torn reads](spikes/27-torn-reads.md) — capturing output and cursor
  position in one tmux invocation.
- [28 Loop filters](spikes/28-loop-filters.md) — a pane lookup and a filtered
  read, each with one `-f` filter.
- [29 Output cuts](spikes/29-output-cuts.md) — `%output` is cut by byte count,
  not by character.
- [30 Server start time](spikes/30-server-start-time.md) — naming a server by
  `#{pid}` and `#{start_time}` together.
- [31 Kotlin read-only](spikes/31-kotlin-read-only.md) — every public
  collection-returning method in the core, checked from Kotlin.

## Design, parity, and studies

Historical and reference material: what was decided, what Python's own surface
and tests look like, and how the two compare.

- [Architecture](design/2026-08-09-architecture.md) — the accepted specification
  the spikes above executed against.
- [Disposable spikes plan](plans/2026-08-09-disposable-spikes.md) — the
  task-by-task plan that produced the spikes and studies below.
- [Architecture review](reviews/2026-08-09-architecture.md) — three independent
  reviews of the specification, before any spike ran.
- [Spike evidence review](reviews/2026-08-09-spike-evidence.md) — an audit of
  what the early spike notes claimed against what still verifies; superseded in
  part.
- [Python API parity](parity/python-api.md) — every public Python declaration,
  and what this port does with it.
- [Python test parity map](parity/test-map.md) — every Python test, mapped to
  the contract test that would port it.
- [CPython subprocess study](studies/cpython-subprocess.md) — what the Java
  transport has to match about CPython's own subprocess handling.
- [Engine-ops seam study](studies/engine-ops-seams.md) — a read-only comparison
  against the Python port's `engine-ops` branch.
- [Java library pattern study](studies/java-library-patterns.md) — patterns and
  counterexamples drawn from released Java libraries, not tutorials.
- [tmux protocol study](studies/tmux-protocol.md) — the released tmux 3.7b
  command-line contract, separated from implementation detail.
