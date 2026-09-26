# Compatibility

The Scala facades build, test and publish with the rest of the repository, so
they meet the same gates the Java library does.

## Compilers and runtimes

Scala 3.9 only — there is no 2.13 build — compiled to JDK 25 bytecode. CI runs
the facades' unit suites, their real-tmux suites, the documented Scala examples
and the runnable programs on JDK 25 and 27 on Linux, and on macOS.

Every lane of the [tmux matrix][tmux-matrix] runs the real-tmux suites against
its own tmux: `3.2a`, `3.3`, `3.3a`, `3.4`, `3.5`, `3.6`, `3.7`, `3.7a`, `3.7b`
and `3.7c`. The Scala fixture takes the lane's tmux the same way the Java one
does.

## Artifacts

| Artifact | Adds |
| --- | --- |
| `libtmux-scala_3` | Direct-style operations and the typed query DSL |
| `libtmux-scala-cats_3` | Cats Effect resources and FS2 observations |
| `libtmux-scala-ox_3` | An Ox `Flow` over subscriptions and live views |

Core's runtime dependencies are the Scala 3 library and `libtmux`. Cats Effect
and FS2 belong to `libtmux-scala-cats`, Ox to `libtmux-scala-ox`. None depends
on Jackson, JUnit, Kotlin, MCP or the workspace packages.

The Scala artifacts release with the Java ones, at the same version, in the
same Central deployment, signed and attested alike. `libtmux-bom` manages them
too, so one BOM version selects a matching Java and Scala set.

## The generated and handwritten surface

Every direct-style extension method and Cats per-operation forward that reaches
tmux is generated from the operation catalog `libtmux` ships, by
[`ScalaOperationGenerator`][generator] — never hand-copied per method, so it
cannot drift from what Java exposes. `WAIT`, `STREAM` and `LIFECYCLE`
operations are written by hand, for their cancellation or resource scoping:
`Server.open`/`fromJava`/`within`/`control`, `Pane.awaitText`/`run`/`await`,
`LiveView`, `LiveServer`, and both modules' `Observation`.

Both facades generate from the same catalog through the same owner-and-kind
branching, and the [generator's tests][generator-tests] hold the two to it: a
captured operation forwards purely on both, and a mutation is effect-wrapped on
the Cats side only. Nothing Scala has shipped yet, so there is no earlier
release for MiMa or `tasty-mima` to compare against.

## Inherited feature boundaries

The facade preserves Java's version guards. [Named buffer deletion][buffers]
rejects tmux before 3.4 because those versions can delete the wrong buffer.
[`Shell.capturing`][java-shell] rejects tmux 3.3a and 3.4, which lose the
requested output. Java's tmux matrix asserts these unsupported results rather
than skip the contract or substitute an empty successful result.

Capture and buffer reads retain Java's normalized text, including its handling
of trailing empty lines. Observations retain decoded text chunks. These APIs
do not promise arbitrary-byte round trips. Sparse hook listings preserve
command order without inventing their original indices. Basic copy-mode entry,
inspection, and exit are wrapped; advanced commands use explicit raw access.
See [execution](execution.md) for control acknowledgement limits, and
[ownership](ownership.md) for resource lifetimes.

[generator]:
  ../../../build-logic/codegen/src/main/kotlin/io/github/libtmux/codegen/scala/ScalaOperationGenerator.kt
[generator-tests]:
  ../../../build-logic/codegen/src/test/kotlin/io/github/libtmux/codegen/scala/ScalaOperationGeneratorTest.kt
[tmux-matrix]:
  ../../../build-logic/conventions/src/main/kotlin/libtmux.tmux-matrix.gradle.kts
[java-options]:
  ../../../libtmux/src/main/java/io/github/libtmux/Options.java
[buffers]:
  ../../../libtmux/src/main/java/io/github/libtmux/Buffers.java
[java-shell]:
  ../../../libtmux/src/main/java/io/github/libtmux/Shell.java
