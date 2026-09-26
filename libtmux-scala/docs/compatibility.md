# Compatibility

The pull-request workflow runs a small Scala smoke matrix. Java CI owns the
cross-version tmux matrix; this workflow verifies the facade on tmux `3.7c`.
Focused local tests establish their own contracts, not an exact-revision CI
result. Java CI alone does not prove the Scala facade, documentation, or
installed artifacts.

## Compilers and runtimes

The [sbt build][build] targets Scala 3.9 only — there is no `2.13` cross-build
— and emits JDK 25 bytecode. The pull-request workflow uses JDK 25. The
[launcher pin][sbt-version] selects sbt 1.12.15; sbt 2 stays a revisit trigger
until `sbt-tasty-mima` publishes an sbt-2 plugin.

The [Java matrix definition][tmux-matrix] supplies the supported tmux range:
`3.2a`, `3.3`, `3.3a`, `3.4`, `3.5`, `3.6`, `3.7`, `3.7a`, `3.7b`, and `3.7c`.
The Scala workflow verifies tmux `3.7c` and checks the selected executable's
actual version.

## Pull-request coverage

| Job | Configuration |
| --- | --- |
| Artifact stage | Linux, JDK 25, Scala 3.9, tmux 3.7c |
| Scala runtime | Linux and macOS, JDK 25, Scala 3.9, tmux 3.7c |
| Installed consumer | Linux, JDK 25, Scala 3.9, tmux 3.7c |

The artifact and runtime jobs run formatting, unit and integration tests,
executed documentation and examples, packaging, and cleanup. The consumer job
runs independent sbt and Gradle consumers against the artifacts staged on
Linux. Source dependencies, direct jar paths, and Maven-local fallback do not
satisfy the consumer check.

A completed job records its source and artifact identities, selected
compiler/JVM/tmux versions, command, exit status, test inventory, and owned
resource cleanup evidence. Missing, skipped, failed, or differently sourced
runs leave that job open.

## Artifacts and Java prerequisites

Both Scala coordinates use group `io.github.libtmux`:

| Artifact | Purpose |
| --- | --- |
| `libtmux-scala_3` | Direct-style operations and the typed query DSL |
| `libtmux-scala-cats_3` | Cats Effect resources and FS2 observations |

Core's runtime dependencies are the Scala library and Java `libtmux`. Cats
Effect and FS2 belong to the separate effect artifact. Core does not require
Jackson, JUnit, Kotlin, MCP, or workspace packages. The build's JUnit and
Jackson dependencies serve integration tests; dependency and installed-POM
checks must verify they do not leak into core.

Development stages the released Java coordinate
`io.github.libtmux:libtmux:0.0.1-alpha.14` in an isolated local repository. It
contains the [Java option prerequisite][java-options] used by this facade. A
released Scala POM pins its available, non-SNAPSHOT Java prerequisite directly.
If a consumer also imports `libtmux-bom`, it must import that same Java version:
a BOM can override the POM pin and select an untested Java artifact. See
[getting started](getting-started.md) for development staging commands.

## Coordinated publication

The Scala build pins sbt 1.12.15 and `sbt-pgp` 2.3.2. `stageSigned` signs both
artifacts into an isolated local Maven stage. Its verifier checks the POM,
binary, source and Scaladoc signatures, their checksums, and a single validated
signing fingerprint. `publicationCoordinates` checks the Scala manifest before
publishing; the artifact stage checks the resulting POMs.

Central publication is deliberately separate from the Java tag workflow. An
owner starts the manual Scala release workflow only after the selected Java
version is available from Central. Central mode excludes the local Java stage,
rejects snapshot and development coordinates, and compiles against that
published Java dependency before signing.

Before importing the signing key, the workflow runs the core, Cats, Ox,
integration, and example tests against that published Java dependency and a
real tmux, so what is signed is what was tested. It then runs `sonaUpload`,
which leaves a pending Central Portal deployment for the owner to publish or
drop, and attests every staged jar and POM with the commit and workflow that
produced them. It never runs `sonaRelease` and does not create a tag, push, or
release artifacts automatically.

## The generated and handwritten surface

Every direct-style extension method and Cats per-operation forward that
reaches tmux is generated, from the Doclet-produced
`operation-catalog.json`, by `ScalaCodegen` (`project/ScalaCodegen.scala`) —
never hand-copied per method, so it cannot drift from what Java actually
exposes the way a parallel hand-written facade could. `WAIT`, `STREAM` and
`LIFECYCLE` operations are handwritten instead, for their bespoke cancellation
or resource scoping: `Server.open`/`fromJava`/`within`/`control`,
`Pane.awaitText`/`run`/`await`, `LiveView`, `LiveServer`, and both modules'
`Observation`. Direct-style and Cats parity is checked at the template level
(`core / codegenSelfTest`, in `build.sbt`): both facades generate from the
same catalog through the same owner-and-kind branching, so a Cats forward that
falls behind a direct-style one fails there rather than in a hand-maintained
omissions file — reflecting over the *compiled* extension methods cannot
compare the two facades directly, since Scala 3 extension methods are not
members of the receiver's class at the JVM level. Nothing Scala has ever
shipped, so there is no previous release for MiMa or `tasty-mima` to compare
against yet; both are revisited starting with the second release.

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

[build]: ../../build.sbt
[sbt-version]: ../../project/build.properties
[tmux-matrix]:
  ../../build-logic/src/main/kotlin/libtmux.tmux-matrix.gradle.kts
[java-options]:
  ../../libtmux/src/main/java/io/github/libtmux/Options.java
[buffers]:
  ../../libtmux/src/main/java/io/github/libtmux/Buffers.java
[java-shell]:
  ../../libtmux/src/main/java/io/github/libtmux/Shell.java
