# Compatibility

The pull-request workflow runs a small Scala smoke matrix. Java CI owns the
cross-version tmux matrix; this workflow verifies the facade on tmux `3.7c`.
Focused local tests establish their own contracts, not an exact-revision CI
result. Java CI alone does not prove the Scala facade, documentation, or
installed artifacts.

## Compilers and runtimes

The [sbt build][build] shares sources between Scala 2.13.18 and 3.3.8 and emits
JDK 21 bytecode. The pull-request workflow uses JDK 21. The [launcher
pin][sbt-version] selects sbt 1.12.15.

The [Java matrix definition][tmux-matrix] supplies the supported tmux range:
`3.2a`, `3.3`, `3.3a`, `3.4`, `3.5`, `3.6`, `3.7`, `3.7a`, `3.7b`, and `3.7c`.
The Scala workflow verifies tmux `3.7c` and checks the selected executable's
actual version.

## Pull-request coverage

| Job | Configuration |
| --- | --- |
| Artifact stage | Linux, JDK 21, Scala 2.13.18, tmux 3.7c |
| Scala runtime | Linux and macOS, JDK 21, Scala 3.3.8, tmux 3.7c |
| Installed consumer | Linux, JDK 21, Scala 2.13.18, tmux 3.7c |

The artifact and runtime jobs run formatting, unit and integration tests,
executed documentation and examples, packaging, and cleanup. The consumer job
runs independent sbt and Gradle consumers against the artifacts staged on
Linux. Source dependencies, direct jar paths, and Maven-local fallback do not
satisfy the consumer check.

A completed job records its source and artifact identities, selected
compiler/JVM/tmux versions, command, exit status, test inventory, and owned
resource cleanup evidence. Missing, skipped, failed, or differently sourced
runs leave that job open.

## Dispatch-only macOS release cells

The existing Scala workflow accepts `include_macos_release=true` only through
manual dispatch. It skips the pull-request smoke jobs and runs the remaining
primary cells P05, P07, and P08 one at a time on macOS with tmux `3.7c`. This
keeps routine pull-request CI at four running jobs while retaining an
executable release-gate path for the remaining macOS producer evidence.

`include_macos_consumers=true` runs the Linux artifact-stage job, then C02
through C07 one at a time on macOS. Each consumer cell uses that Linux stage
and runs both independent sbt and Gradle consumers. It also skips the routine
pull-request jobs, so the installed-consumer release gate does not expand the
pull-request matrix.

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

Development stages the released Java coordinate
`io.github.libtmux:libtmux:0.0.1-alpha.14` in an isolated local repository. It
contains the [Java option prerequisite][java-options] used by this facade. A
released Scala POM pins its available, non-SNAPSHOT Java prerequisite directly.
If a consumer also imports `libtmux-bom`, it must import that same Java version:
a BOM can override the POM pin and select an untested Java artifact. See
[getting started](getting-started.md) for development staging commands.

## Coordinated publication

The Scala build pins sbt 1.12.15 and `sbt-pgp` 2.3.2. `stageSigned` signs all
four artifacts into an isolated local Maven stage. Its verifier checks the POM,
binary, source and Scaladoc signatures, their checksums, and a single validated
signing fingerprint. `publicationCoordinates` checks the Scala manifest before
publishing; the artifact stage checks the resulting POMs. The Gradle gate
checks the Scala-excluding BOM against Gradle-published artifacts.

Central publication is deliberately separate from the Java tag workflow. An
owner starts the manual Scala release workflow only after the selected Java
version is available from Central. Central mode excludes the local Java stage,
rejects snapshot and development coordinates, and compiles against that
published Java dependency before signing.

The workflow runs `sonaUpload`, which leaves a pending Central Portal
deployment for the owner to publish or drop. It never runs `sonaRelease` and
does not create a tag, push, or release artifacts automatically.

## The wrapped surface

A public method on `Server`, `Session`, `Window`, `Pane`, or `Client` is
either wrapped by the blocking facade or named in
`src/test/resources/scala-java-omissions.txt`. A captured field is read from
`info`. Anything else stays on the Java handle. `SurfaceSuite` reads both from
the compiled classes and fails when a new Java method is neither, and when a
wrapped method gains a Java overload with no Scala form taking the same
arguments, directly or through default arguments. An overload left out on
purpose gets its own line, such as `Server.newSession(Function1)`, whose Java
builder lambda the facade replaces with a built spec.

## The binary surface

Every public class, constructor, method, and field of both artifacts, as the
JVM sees them, is committed under [`libtmux-scala/api/`][api], one file per
artifact and Scala binary family. `ApiManifestSuite` fails when the compiled
surface differs, so every signature change is a reviewed line rather than a
surprise to a consumer's linker. Members that Scala keeps package-private are
public bytecode and are listed too, as MiMa would see them. Nothing Scala is
released yet, so there is no previous jar for MiMa to compare against; after a
deliberate change, regenerate on both families and review the diff:

```console
$ LIBTMUX_SCALA_API_WRITE=1 libtmux-scala/sbtw \
    '++2.13.18' 'cats/testOnly *ApiManifestSuite' \
    '++3.3.8' 'cats/testOnly *ApiManifestSuite'
```

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
See [execution](execution.md) for control acknowledgement and batch attribution
limits, and [ownership](ownership.md) for resource lifetimes.

[api]: ../api/
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
