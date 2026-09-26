# docs

**An index of what remains: task-oriented guides, the generated reference and
benchmark pages, decisions still in force, and background reference
material.**

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
- [Operating a service](guide/operating-a-service.md) — driving tmux from a
  service: retries, telemetry, and pane input.
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

## Reference and benchmarks

Generated; do not edit by hand.

- [Operation catalog](reference/operations.md) — every `@Operation`, generated
  from its annotation.
- [Operation costs](benchmarks/operation-costs.md) — measured wall-clock and
  tmux-process cost for one-at-a-time, batched, and chained calls; regenerated
  by `./gradlew operationBenchmark`.
- [Scala facade costs](../libtmux-scala/benchmarks/README.md) — the blocking
  and Cats Effect facades against the Java core, with warmups, raw samples, and
  allocated bytes; one committed run in `results/`.

## Decisions

Short ADRs for decisions still in force, each citing the tmux (or JDK, Gradle,
or Kotlin) behaviour that forced it. Format: Status, Context, Decision,
Consequences.

- [0001 Build-logic convention build](decisions/0001-build-logic-convention-build.md)
- [0002 Blocking process transport](decisions/0002-blocking-process-transport.md)
- [0003 Hierarchy hydration, per-entity listings](decisions/0003-hierarchy-hydration-per-entity-listings.md)
- [0004 Query expressions, hand-written metamodel](decisions/0004-query-expressions-hand-written-metamodel.md)
- [0005 Pushdown lowering is exact or refused](decisions/0005-pushdown-lowering-is-exact-or-refused.md)
- [0006 Real-tmux JUnit 5 fixture lifecycle](decisions/0006-real-tmux-junit5-fixture-lifecycle.md)
- [0007 Row framing with a random separator](decisions/0007-row-framing-with-a-random-separator.md)
- [0008 Control-mode framing and quoting](decisions/0008-control-mode-framing-and-quoting.md)
- [0009 Command groups are transport-agnostic](decisions/0009-command-groups-are-transport-agnostic.md)
- [0010 Single process carrier, no execution mode](decisions/0010-single-process-carrier-no-execution-mode.md)
- [0011 Wait outcomes name why a wait ended](decisions/0011-wait-outcomes-name-why-a-wait-ended.md)
- [0012 Creation specs are builder-built](decisions/0012-creation-specs-are-builder-built.md)
- [0013 Server identity is pid and start time](decisions/0013-server-identity-is-pid-and-start-time.md)
- [0014 Watch a server with refresh-client](decisions/0014-watch-a-server-with-refresh-client.md)
- [0015 Atomic capture and cursor position](decisions/0015-atomic-capture-and-cursor-position.md)
- [0016 libtmux-mcp serves synchronously](decisions/0016-libtmux-mcp-serves-synchronously.md)
- [0017 Kotlin sees core collections as read-only](decisions/0017-kotlin-sees-core-collections-as-read-only.md)
- [0018 Control-backed transport rejected](decisions/0018-control-backed-transport-rejected.md)

## Internals

- [tmux behaviour](internals/tmux-behaviour.md) — version quirks and protocol
  facts cited from source comments, kept in one lean, reachable place.

## Parity with Python libtmux

- [Python API parity](parity/python-api.md) — every public Python declaration,
  and what this port does with it.
- [Python test parity map](parity/test-map.md) — every Python test, mapped to
  the contract test that would port it.

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

Not `docs/decisions` or `docs/internals`: those are dated or historical
records. Holding them to today's API would either break the build or quietly
rewrite history, and neither is what a record is for.
