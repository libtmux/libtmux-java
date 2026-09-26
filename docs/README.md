# docs

**An index of this directory: task-oriented guides first, then the dated record
of how the library got here.**

API reference: [current trunk](https://libtmux.org/en/java/latest/reference/),
or [a released version](https://javadoc.io/doc/io.github.libtmux/libtmux) on
javadoc.io.

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
- [Scala facade costs](../libtmux-scala/benchmarks/README.md) — the blocking
  and Cats Effect facades against the Java core, with warmups, raw samples, and
  allocated bytes; one committed run in `results/`.

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

## How these pages are tested

This directory is also a build module. It compiles and runs the code in every
guide and README, and checks the claims around it.

A snippet is the part of a project people copy and the part nothing compiles, so
it goes stale silently — and a stale snippet reads exactly as well as a working
one. This module puts every Java fence in the READMEs and guides through javac
against the real artifacts, and then runs it against a real tmux server.

```console
$ ./gradlew :docs:test
```

One case per snippet, named for the file and line it came from, so a failure says
where to look. One tmux server per case, from
[`libtmux-junit5`](../libtmux-junit5/), so a snippet that makes a session gets a
server nobody else is using.

### What a snippet is checked for

By default: **it must compile and it must run.** Compiling proves the API has the
shape the document describes; running proves the document is right about what
happens, which is what a reader depends on and what a compiler cannot check.

Say otherwise with an HTML comment directly above the fence:

| directive | means |
| --- | --- |
| *(none)* | compiles, and runs against live tmux |
| `<!-- snippet: throws: IllegalArgumentException -->` | runs, and must fail with exactly that |
| `<!-- snippet: does-not-compile: cannot find symbol -->` | the compiler must reject it, with a diagnostic containing that phrase |
| `<!-- snippet: compile-only: <reason> -->` | compiles; not run, for the stated reason |
| `<!-- snippet: skip: <reason> -->` | not checked, for the stated reason |

The comment has to sit directly above the fence, with nothing between them. An
unrecognised directive fails the build: a snippet nobody is checking, because of
a typo in the thing that says how to check it, is the state this exists to
prevent.

`throws:` names the exception's simple name, not its package — the comparison is
against `getClass().getSimpleName()`, so `IllegalArgumentException` matches and
`java.lang.IllegalArgumentException` does not.

A block that declares a type — a `class`, `record`, `interface` or `enum` — is
compiled and never run, whatever its directive says, because a declaration has
nothing to execute. Statements are wrapped in a method body, with whatever the
fence's own `Given:` line asked for (below) in scope; a type is compiled as it
stands and never sees one.

`does-not-compile` earns its keep: it is what keeps
`Pane_.index().startsWith("2")` an error. A README claiming the compiler rejects
something would otherwise survive the day it stopped being true. The phrase is
what makes the rejection the documented one: without it, a typo elsewhere in
the block would pass for the error the prose describes.

Every snippet that runs has 15 seconds. A snippet that hangs fails on its own
line instead of hanging the build.

### Showing what a call returns

A line ending in an arrow is an assertion:

<!-- snippet: compile-only: shows the syntax; the values belong to a session this fixture does not have -->
```java
// Given: Server server, Session session
session.name();                      // → demo
server.sessions().size();            // → 2
server.hasSession("demo");           // → true
```

Python's doctest is why the sibling library's README can show what every call
returns and still be trusted. Java has no doctest, so this is one: the value after
the arrow is compared against `String.valueOf(…)` of the expression above it, and
a README cannot claim a value the library does not produce.

Comparing as text means one rule covers a string, a number, a boolean and a list
without a comment having to contain Java literals — what you see after the arrow
is exactly what `toString` gave.

Two consequences worth knowing:

- **Everything after the arrow is the expected value**, so prose cannot trail it.
  Put the explanation on its own comment line above.
- **The value is trimmed**, so one with a leading or trailing space cannot be
  expressed this way. Assert it in a test instead.
- **The expression has to fit on the line the arrow is on.** A call split across
  lines leaves the rewriter with a fragment, which fails to compile rather than
  failing quietly — put the value in a local first.

### What a snippet may assume

Documentation shows the interesting line, not the ones before it that made a
server — but leaning on one of those without saying so is a snippet a reader
cannot paste and run, whatever it proves to this build. So the harness offers
nothing by default. A snippet that needs one declares it, visibly, as the first
line inside the fence:

```java
// Given: Server server
Session session = server.newSession("demo");
```

`Given:` is not an HTML comment above the fence like the directives below — it
is inside it, in the language the fence is written in, so it is part of what a
reader sees and copies, not part of the machinery checking it. The names on
offer are `server` (`Server`), `config` (`ServerConfig`), `session` (`Session`),
`window` (`Window`), `pane` (`Pane`), `options` (`Options`), `socket` (`Path`),
`directory` (`Path`), `timeout` (`Duration`) and `yamlString` (`String`); several
go on one line, comma-separated: `// Given: Server server, Session session`. A
snippet declaring its own `server` shadows the supplied one, which is what a
reader copying it would get anyway.

The declaration is held to exactly what the snippet uses, in both directions:

- **Uses a name it did not declare** fails to compile as an ordinary "cannot
  find symbol" — nothing of that name exists on the harness the snippet asked
  for.
- **Declares a name it never reads** fails too, for that reason specifically.
  Java has no "declared and not used" error for a field the way some languages
  do for a local, so this half is checked textually: comments are stripped
  (the `Given:` line itself included) and the rest is searched for the name as
  a whole word. A name mentioned only in prose does not count as used, and a
  name inside a string this check cannot tell from code would be a false
  negative it does not try to catch — keep a `Given:` line to real bindings and
  this does not come up.

A fence with no `Given:` line gets nothing and must be self-contained.
Consequently a fence cannot depend on a variable another fence declared, a
harness field it never asked for, or on being read in the order it prints —
and neither can a reader who copies just that fence.

[`SnippetCompilerTest`](src/test/java/io/github/libtmux/docs/SnippetCompilerTest.java)
pins this mechanism directly, the way go's own doc-generator pins the same
property for its regions: a binding declared and used compiles, one used but
not declared fails as "cannot find symbol", and one declared but not used fails
as unused — with no tmux server needed for any of the three, since compiling a
snippet never starts one.

### Kotlin fences

This module reads Java. The Kotlin fences in the root README, `libtmux-kotlin`'s
README and the Kotlin guide are checked a different way: `libtmux-kotlin` has a
`generateDocumentationSnippets` task that turns each one into a test function, and
the ordinary Kotlin compilation and test run do the checking.

Generating a source file rather than running the Kotlin compiler in-process is the
same guarantee by a shorter road — and because the generated file *is* the
documentation, the two cannot drift.

```console
$ ./gradlew :libtmux-kotlin:test
```

A Kotlin fence gets `server` and nothing else unless its first line asks, the
same rule as the Java `Given:` line: `// Given: config: ServerConfig` or
`// Given: session: Session, window: Window, pane: Pane`. The names on offer are
`config`, `session`, `window`, `pane` and `socket`, and a name outside them fails
the task. Only one direction is checked: a Kotlin fence that uses a name it did
not ask for fails to compile, but one that asks for a name it never reads is not
caught, because the generated file suppresses unused-variable warnings.

### Claims that are not code

A snippet is executed, so it cannot lie. A version in an install block, or a
list of what the platform manages, is prose — and prose is what is still wrong
six months later, in the one place every reader starts. Those are checked too:

| what is checked | where it looks |
| --- | --- |
| Every coordinate names the version this build would publish | the root README, `libtmux-bom`'s, every published module's, the Kotlin and Scala guides, and `RELEASING.md` |
| `libtmux-bom`'s README lists exactly what the platform constrains | that README against `libtmux-bom/build.gradle.kts` |
| Every published module's README names it first and states its coordinate | each published module's README |
| A fence in a source language nothing here builds carries a directive saying so | every reader-facing document |
| The contract tests the parity documents cite are unwritten or really declared | `docs/parity/python-api.md`, `docs/parity/test-map.md` |
| Those documents keep saying "planned parity" while those tests are unwritten | the same two |

The last two are why this module reads documents it takes no snippets from.
`docs/parity/` holds no Java, and `RELEASING.md` is not a place snippets come
from, but a coordinate in either is a claim like any other.

The snippet suite also asserts a floor on how much it found. A filter or a
rename can reduce a parameterised suite to nothing without failing anything, and
a suite that discovers nothing passes loudly.

### Which documents

Snippets come from `README.md`, `MIGRATION.md`, every package's `README.md`, and
every guide under `docs/guide/`. The checks above that are not about snippets read more than
that, and each row says where it looks.

Not `docs/spikes`, `docs/plans` or `docs/studies`: those are dated records of what
was measured or decided at the time. Holding them to today's API would either
break the build or quietly rewrite history, and neither is what a record is for.
