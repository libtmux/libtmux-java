# Contributing

## The gate

One command has to pass before anything is proposed:

```console
$ ./gradlew check
```

It runs formatting, Error Prone, NullAway, and every test including the ones
that start real tmux servers. A green `check` that reported `UP-TO-DATE` for
every task verified nothing; force it with `--rerun-tasks` when that matters.

Against every supported tmux release, which is what the compatibility claim in
the README rests on:

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

If a real-tmux failure looks like a regression, count what is already running
before believing it — this machine routinely carries several hundred tmux servers
belonging to sibling ports:

```console
$ ./scripts/reap-stale-servers.sh
```

## Tests start real tmux

Every server this project starts belongs under a path naming this port —
`/tmp/libtmux-java-test/` for tests, `/tmp/libtmux-java-dev/` for anything
started by hand. `AGENTS.md` explains why in full, and it is not tidiness: this
machine runs several libtmux ports at once, and their leftovers turn into this
suite's intermittent failures.

## What a change is expected to carry

- **A test that failed before it.** For a bug, that test is the evidence the bug
  was real; without it there is nothing to distinguish a fix from a coincidence.
- **A measurement, when the claim is about tmux.** tmux's behaviour differs
  across the supported range in ways no amount of reading settles. Notes under
  `docs/spikes/` record what was measured and against which release.
- **Nothing generated-looking.** Names that say what a thing is for, comments
  that say why rather than what, no abstraction without a second caller.

## Where things live

A directory is a published artifact exactly when it declares a Maven publication,
and `platformCoversEveryPublishedModule` fails the build when that set stops
matching `libtmux-bom`. Nothing about this is a convention you have to remember.

Note the wording: *declares a publication*, not *applies the publishing plugin*.
Those came apart once already — `libtmux-kotlin` applied `libtmux.publication`,
which configures publications rather than creating one, so it looked published to
the build and released no jar. A `publishToMavenLocal` found it; the gate now
asks the question that would have.

| directory                        | published | holds                                     |
| -------------------------------- | --------- | ----------------------------------------- |
| `libtmux*/`                      | yes       | one artifact each, named for its directory |
| `integration-tests/`             | no        | the real-tmux suite, which spans artifacts |
| `benchmarks/`                    | no        | the carrier measurements                   |
| `examples/`                      | no        | whole runnable programs, run by its own suite |
| `scripts/`                       | no        | what the build does not do                 |
| `build-logic/`                   | no        | convention plugins, as an included build   |
| `docs/`, `gradle/`, `.github/`   | no        | everything else                            |

The suite lives outside every published module on purpose. A suite inside one
artifact's tests makes that artifact's dependencies and lifecycle answerable for
how the whole library is tested.

## Commit messages

Imperative subject, then why the change was needed, then what it did:

```
Refuse a request control mode cannot answer

why: Control mode frames a reply per command, so a request holding
several produced several replies for one awaited request, and the
extras were matched to whatever asked next.

what:
- Refuse a command group before writing anything
```

No emoji, no prefixes, no trailing attribution.

## Carriers

`ExecutionMode` chooses how a command travels. A suite can run under any of
them, and a failure that appears under only one is a real finding:

```console
$ LIBTMUX_MODE=control ./gradlew check
```

Confirm it against a clean `/tmp` first. Cross-port debris and a genuine carrier
defect look alike.
