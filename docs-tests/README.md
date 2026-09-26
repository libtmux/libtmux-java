# docs-tests

**Compiles and runs the code in the documentation, and checks the claims around
it. Not published.**

A snippet is the part of a project people copy and the part nothing compiles, so
it goes stale silently — and a stale snippet reads exactly as well as a working
one. This module puts every Java fence in the READMEs and guides through javac
against the real artifacts, and then runs it against a real tmux server.

```console
$ ./gradlew :docs-tests:test
```

One case per snippet, named for the file and line it came from, so a failure says
where to look. One tmux server per case, from
[`libtmux-junit5`](../libtmux-junit5/), so a snippet that makes a session gets a
server nobody else is using.

## What a snippet is checked for

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

## Showing what a call returns

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

## What a snippet may assume

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

## Kotlin fences

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

## Claims that are not code

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

## Which documents

Snippets come from `README.md`, `MIGRATION.md`, every package's `README.md`, and
`docs/guide/*.md`. The checks above that are not about snippets read more than
that, and each row says where it looks.

Not `docs/spikes`, `docs/plans` or `docs/studies`: those are dated records of what
was measured or decided at the time. Holding them to today's API would either
break the build or quietly rewrite history, and neither is what a record is for.

## Next

- [`examples/`](../examples/) — whole runnable programs, checked the same way
- [Root README](../README.md)
