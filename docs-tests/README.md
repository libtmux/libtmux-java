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
| `<!-- snippet: does-not-compile -->` | the compiler must reject it |
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
nothing to execute. Statements are wrapped in a method body with the fixtures
below in scope; a type is compiled as it stands.

`does-not-compile` earns its keep: it is what keeps
`Pane_.index().startsWith("2")` an error. A README claiming the compiler rejects
something would otherwise survive the day it stopped being true.

## Showing what a call returns

A line ending in an arrow is an assertion:

<!-- snippet: compile-only: shows the syntax; the values belong to a session this fixture does not have -->
```java
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
server. Those are supplied: `server`, `config`, `session`, `window`, `pane`,
`options`, `socket`, `directory`, `timeout`, `yamlString`, and the common
imports. A snippet declaring its own `server` shadows the supplied one, which is
what a reader copying it would get anyway.

Consequently a fence cannot depend on a variable another fence declared — and
neither can a reader who copies just that fence.

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

Snippets come from `README.md`, every package's `README.md`, and
`docs/guide/*.md`. The checks above that are not about snippets read more than
that, and each row says where it looks.

Not `docs/spikes`, `docs/plans` or `docs/studies`: those are dated records of what
was measured or decided at the time. Holding them to today's API would either
break the build or quietly rewrite history, and neither is what a record is for.

## Next

- [`examples/`](../examples/) — whole runnable programs, checked the same way
- [Root README](../README.md)
