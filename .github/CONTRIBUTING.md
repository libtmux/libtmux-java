# Contributing

libtmux for Java is a multi-project Gradle build plus `build-logic`, an included
build holding the convention plugins. The gates below are what a change has to
pass before it is proposed.

How this project writes prose — README, changelog, release notes, commit
messages, API documentation, and source comments — is set out separately in
[`WRITING.md`](WRITING.md). Read that before changing any of it.

A directory is a published artifact exactly when it declares a Maven
publication, and the build fails when that set stops matching `libtmux-bom`.
Nothing about this is a convention you have to remember:

| directory | published | holds |
| --- | --- | --- |
| `libtmux*/` | yes | one artifact each, named for its directory |
| `integration-tests/` | no | the real-tmux suite, which spans artifacts |
| `benchmarks/` | no | the carrier measurements |
| `examples/` | no | whole runnable programs, run by its own suite |
| [`docs-tests/`](../docs-tests/) | no | compiles and runs every snippet in the docs |
| `scripts/` | no | what the build does not do |
| `build-logic/` | no | convention plugins, as an included build |
| `docs/`, `gradle/`, `.github/` | no | everything else |

The real-tmux suite lives outside every published module on purpose. A suite
inside one artifact's tests makes that artifact's dependencies and lifecycle
answerable for how the whole library is tested.

## Building

You need JDK 21 or newer and tmux on `PATH`. Nothing else — Gradle provisions
the toolchain, and the library has no runtime dependencies.

`./gradlew` is the only supported entry point. A locally installed `gradle` is
not: the wrapper pins the version the build was written against, and
`build-logic` is an included build that a mismatched Gradle will configure
differently.

```console
$ ./gradlew build
```

## Running the tests

**Every tmux server this project starts belongs under a path that names this
port.** Use one of:

- `/tmp/libtmux-java-test/…` — anything a test starts.
- `/tmp/libtmux-java-dev/…` — anything started by hand while investigating.

Never `/tmp/libtmux-…` on its own, and never the default socket. `libtmux-` is
the prefix a sibling port is also using, which is the whole problem.

Sibling libtmux ports — Python, Go, Rust, TypeScript, C#, C++, Swift — are
worked on in parallel on the same machine, and every one of them starts real
tmux servers in `/tmp`. Their debris outlives their test runs: servers from an
exited benchmark stay up, holding ptys, until something kills them.

Why that matters more than tidiness: a suite sharing `/tmp` with another port's
leftovers fails intermittently, under load, in places that have nothing to do
with the change under test — `SERVER_GONE`, misframed rows, `fork: No space
left on device`. Every one of those reads as a regression in whatever you last
touched, and hours go into a bug that was never yours.

Before blaming a change for an intermittent real-tmux failure, count what is
already running:

```console
$ pgrep -c tmux
```

Then find whose it is, since a socket path exists only in the process's own
command line:

```console
$ for p in $(pgrep tmux); do tr '\0' ' ' < /proc/$p/cmdline | rg -o '\-S [^ ]+'; done
```

Kill only sockets under this port's roots. Another port's servers are not yours
to reap, however much they cost us:

```console
$ ./scripts/reap-stale-servers.sh
```

A unix socket path cannot exceed about 104 bytes, and tmux reports a longer one
as `error connecting to … (File name too long)`. That rules out sockets under a
build directory or a deep scratch path, and it is why the roots above are short.

`ExecutionMode` chooses how a command travels, and the suite can be run under
any of them:

```console
$ LIBTMUX_MODE=control ./gradlew check
```

A failure that appears only under one carrier is a real finding — the library
claims the answer does not depend on the carrier, and
`ExecutionModeConformanceTest` is where that claim is gated. Confirm it against
a clean `/tmp` first: the failure modes of cross-port debris and of a genuine
carrier defect look alike.

Against every supported tmux release:

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

The matrix is a local tree of built tmuxes, one directory per lane, each with
`bin/tmux`. Build one:

```console
$ ./scripts/tmux-matrix.sh ~/tmux-builds
```

It reads the lane list out of the build, so it cannot drift from what the matrix
actually runs.

## Checks that must pass

One command has to pass before anything is proposed:

```console
$ ./gradlew check
```

It runs formatting, Error Prone, NullAway in JSpecify mode, and every test
including the ones that start real tmux servers.

The Java in the documentation is compiled and run as part of that, and the
claims around it — the version in every install block, what the platform says it
manages — are checked with it:

```console
$ ./gradlew :docs-tests:test
```

How a fence says what it is — the directives, the `// →` assertions, the
fixtures a snippet may assume — is in
[`docs-tests/README.md`](../docs-tests/README.md), beside the code that reads
them.

A green `check` that reported `UP-TO-DATE` for every task verified nothing.
Force it when that matters:

```console
$ ./gradlew check --rerun-tasks
```

Check the exit status rather than the last lines of output. Piping to `tail`
reports the pipe's status, which hides `BUILD FAILED`.

`platformCoversEveryPublishedModule` fails the build when the set of published
directories stops matching `libtmux-bom`. Note the wording used above: *declares
a publication*, not *applies the publishing plugin*. Those came apart once
already — `libtmux-kotlin` applied `libtmux.publication`, which configures
publications rather than creating one, so it looked published to the build and
released no jar. A `publishToMavenLocal` found it; the gate now asks the
question that would have.

The tmux matrix is not part of `check`, and a green `check` has not predicted
it. Run the matrix before a release.

## Pull requests

One subject per pull request. Unrelated cleanup found along the way belongs in
its own commit, and usually in its own pull request.

What a change is expected to carry:

- **A test that failed before it.** For a bug, that test is the evidence the bug
  was real; without it there is nothing to distinguish a fix from a coincidence.
  A passing gate is evidence only once it has been shown capable of failing.
- **A measurement, when the claim is about tmux.** tmux's behaviour differs
  across the supported range in ways no amount of reading settles. Notes under
  `docs/spikes/` record what was measured and against which release.
- **A changelog entry, when a caller can observe the change.** It goes under
  `## Unreleased`. Nothing enforces this, which is why it is written down.
- **Nothing generated-looking.** Names that say what a thing is for, comments
  that say why rather than what, no abstraction without a second caller.

Commit format is in [`WRITING.md`](WRITING.md). The constraints every change is
held to, whatever it touches, are in [`AGENTS.md`](../AGENTS.md).

## Review

Review is the maintainer reading the diff against what this file already asks
for: the gates ran, the change carries what `Pull requests` says it must, and
the scope matches what was asked for.

Nothing here is automated beyond the checks on the pull request, and there is no
approval quorum — this is a small project and says so rather than describing a
process it does not run.

Disagreement about a rule is resolved by changing the rule in this file or in
[`WRITING.md`](WRITING.md), not by making an exception in review.

## Releases

Never create tags. Never push tags. The owner handles tagging and tag pushes,
because a tag triggers the publish workflow, which signs the artifacts and
pushes them to the Central Portal.

A release commit subject is plain and short: `Tag v<version>`.

How a release is actually cut — the version property, the signing key, the order
to do things in — is in [`RELEASING.md`](../RELEASING.md).

## Compatibility

**JDK 21 is the floor.** Three places state it and all three have to agree: the
toolchain and `options.release` in
`build-logic/src/main/kotlin/libtmux.java-library.gradle.kts`, the version
matrix in the CI workflow, which builds on 21 and 25, and the claim `README.md`
makes under `Requirements`.

**tmux 3.2a through 3.7b is the supported range**, and it is not a claim: the
whole real-tmux suite runs against every one of those releases, and each lane
checks it really ran the tmux it is named after. Moving the range means moving
`workflows/tmux-matrix.yml` and the README together.

**The API carries no compatibility guarantee.** This project is alpha: releases
carry an `-alpha` prerelease tag, any release may change or remove exported
identifiers without a deprecation period, and only the newest release is
supported. Pin an exact version.

That is a statement about the Java API, not about tmux correctness. Behaviour
against the supported tmux range is gated on every push, and a change there is a
bug rather than a liberty the alpha terms allow.
