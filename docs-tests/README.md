# docs-tests

**Compiles and runs the code in the documentation. Not published.**

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

An unrecognised directive fails the build. A snippet nobody is checking, because
of a typo in the thing that says how to check it, is the state this exists to
prevent.

`does-not-compile` earns its keep: it is what keeps
`Pane_.index().startsWith("2")` an error. A README claiming the compiler rejects
something would otherwise survive the day it stopped being true.

## What a snippet may assume

Documentation shows the interesting line, not the six before it that made a
server. Those six are supplied: `server`, `config`, `session`, `window`, `pane`,
`options`, `socket`, `directory`, `timeout`, and the common imports. A snippet
declaring its own `server` shadows the supplied one, which is what a reader
copying it would get anyway.

Consequently a fence cannot depend on a variable another fence declared — and
neither can a reader who copies just that fence.

## Which documents

`README.md`, every package's `README.md`, and `docs/guide/*.md`.

Not `docs/spikes`, `docs/plans` or `docs/studies`: those are dated records of what
was measured or decided at the time. Holding them to today's API would either
break the build or quietly rewrite history, and neither is what a record is for.

## Next

- [`examples/`](../examples/) — whole runnable programs, checked the same way
- [Root README](../README.md)
