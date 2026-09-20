# Compatibility

The versions below are verification targets. Full Scala matrix and installed
consumer coverage remain unverified. Focused local tests establish their own
contracts, not completion of a matrix cell. Java CI results alone do not prove
the Scala facade, its documentation, or its installed artifacts.

## Compilers and runtimes

The [sbt build][build] shares sources between Scala 2.13.18 and 3.3.8 and emits
JDK 21 bytecode. Required runtime targets are JDK 21 and 25 on Linux and macOS.
Scala 3.9.0 is a downstream consumer target for the artifacts produced by
3.3.8; it does not replace that producer. The [launcher pin][sbt-version]
selects sbt 1.12.15.

The required tmux releases come from the [Java matrix definition][tmux-matrix]:
`3.2a`, `3.3`, `3.3a`, `3.4`, `3.5`, `3.6`, `3.7`, `3.7a`, `3.7b`, and `3.7c`.
Preview lanes `3.8-rc` and `master` do not expand that set. A test must verify
the selected executable's actual version; an extra installed release cannot
substitute for a required lane.

## Required coverage

The plan uses factorized coverage, with 26 distinct producer/runtime cells.
It does not claim the full 80-cell OS/JDK/producer/tmux Cartesian product.

| Group | Required combinations |
| --- | --- |
| Primary: 8 cells | Both producers, both JDKs, both OSes, tmux 3.7c |
| tmux: 18 more cells | Both producers, Linux/JDK 21, each other required tmux |
| Consumers: 12 cells | Both OSes/JDKs, Scala 2.13.18, 3.3.8 and 3.9.0 |

Each primary cell includes compilation, formatting, unit and integration
tests, executed documentation and examples, packaging, and cleanup. The tmux
cells run the complete integration and executed-example contracts. The two
Linux/JDK 21 primary cells also supply tmux 3.7c coverage when their source,
artifacts, test inventory, invocation, and selected binary match. Older tmux
releases on macOS or JDK 25 are outside this factorized set.

Each consumer cell must run through an independent sbt build and an independent
Gradle or Maven build: 24 build-tool runs, using tmux 3.7c. All consume the same
four artifacts staged from Linux/JDK 21, with recorded hashes. Rebuilding jars
on each platform would not prove that the installed distribution travels
between them. Source dependencies, direct jar paths, and Maven-local fallback
do not satisfy these checks.

A completed cell needs the exact source and artifact identities, actual
compiler/JVM/tmux versions, command, exit status, whole-command duration,
executed test inventory, and owned-resource cleanup evidence. Missing, skipped,
failed, or differently sourced runs leave that cell open. A local run does not
establish an exact-revision remote CI result.

## Artifacts and Java prerequisites

All four Scala coordinates use group `io.github.libtmux`:

| Binary family | Core artifact | Optional effect artifact |
| --- | --- | --- |
| Scala 2.13 | `libtmux-scala_2.13` | `libtmux-scala-cats_2.13` |
| Scala 3 | `libtmux-scala_3` | `libtmux-scala-cats_3` |

Core's runtime dependencies are the Scala library and Java `libtmux`. Cats
Effect and FS2 belong to the separate effect artifact. Core does not require
Jackson, JUnit, Kotlin, MCP, or workspace packages. The build's JUnit and
Jackson dependencies serve integration tests; dependency and installed-POM
checks must verify they do not leak into core. Keep the consumer's Scala binary
family consistent across both artifacts.

Development uses the exact Java coordinate
`io.github.libtmux:libtmux:0.0.1-alpha.12-scala-dev.1` from an isolated local
stage. It includes the [Java option prerequisite][java-options] used by this
facade. This is not a public release claim. A released Scala POM must instead
pin an available, non-SNAPSHOT Java version containing that prerequisite.
See [getting started](getting-started.md) for development staging commands.

## Coordinated publication

The Scala build pins sbt 1.12.15 and `sbt-pgp` 2.3.2. `stageSigned` signs all
four artifacts into an isolated local Maven stage. Its verifier checks the POM,
binary, source and Scaladoc signatures, their checksums, and a single validated
signing fingerprint. A separate local BOM stage compares the actual published
Scala constraints with the staged Scala POM set; the Gradle gate compares the
same manifest with all Gradle publications.

Central publication is deliberately separate from the Java tag workflow. An
owner starts the manual Scala release workflow only after the selected Java
version is available from Central. Central mode excludes the local Java stage,
rejects snapshot and development coordinates, and compiles against that
published Java dependency before signing. The current development Java
coordinate above is therefore not release-eligible.

The workflow runs `sonaUpload`, which leaves a pending Central Portal
deployment for the owner to publish or drop. It never runs `sonaRelease` and
does not create a tag, push, or release artifacts automatically.

## Inherited feature boundaries

The facade preserves Java's version guards. [Named buffer deletion][buffers]
rejects tmux before 3.4 because those versions can delete the wrong buffer.
[`runShellCapturing`][java-server] rejects tmux 3.3a and 3.4, which lose the
requested output. Required lanes must assert these unsupported results rather
than skip the contract or substitute an empty successful result.

Capture and buffer reads retain Java's normalized text, including its handling
of trailing empty lines. Observations retain decoded text chunks. These APIs
do not promise arbitrary-byte round trips. Sparse hook listings preserve
command order without inventing their original indices. Basic copy-mode entry,
inspection, and exit are wrapped; advanced commands use explicit raw access.
See [execution](execution.md) for control acknowledgement and batch attribution
limits, and [ownership](ownership.md) for resource lifetimes.

[build]: ../build.sbt
[sbt-version]: ../project/build.properties
[tmux-matrix]:
  ../../build-logic/src/main/kotlin/libtmux.tmux-matrix.gradle.kts
[java-options]:
  ../../libtmux/src/main/java/io/github/libtmux/Options.java
[buffers]:
  ../../libtmux/src/main/java/io/github/libtmux/Buffers.java
[java-server]:
  ../../libtmux/src/main/java/io/github/libtmux/Server.java
