# Integrated synthesis

## Status

Partial. A vertical slice built from the five frozen contracts publishes three
artifacts and is driven end to end by independent Maven and Gradle consumers.
The full re-run of every prior hard gate inside the integrated tree is not done;
what did and did not run is listed under [Gates](#gates).

## Frozen contracts

Written from the five bakeoff notes before any integrated source existed, so
integration implements decisions rather than reopening them.

| area      | selected                                                             | graft carried in                                         | rejected                                       |
| --------- | -------------------------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------- |
| build     | included `build-logic` convention build; `com.git-pull:libtmux`      | vendored publication oracle                              | single-project variants; direct multi-project  |
| transport | admission-bounded prestarted platform pumps; gated launch section    | retrying destroy escalation; permit held until reclaimed | virtual-thread drains; temporary-file redirect |
| hydration | one server-wide listing per entity kind, four commands               | server fields ride the session rows                      | scoped per-window listings; single pane rowset |
| query     | sealed `FilterExpr` implementing `Predicate`; hand-written metamodel | normalized condition IR before any lowering              | JSR 269 generation; restricted remote type     |
| lifecycle | store-held fixtures released from a callback, idempotent aggregate   | release independent of store auto-close                  | extension instance fields; caller-closed       |

## What the slice is

Three published modules under the selected coordinates, package root
`io.github.libtmux`:

- `libtmux` — transport, snapshot, query, the hand-written metamodel, and a
  `Server` facade carrying the live operations the journey needs;
- `libtmux-jackson` — the versioned JSON filter form;
- `libtmux-junit5` — the lifecycle extension.

The query core is carried across intact rather than retyped. It is the frozen
contract itself, and the compile-fail proof, the drift guard and the JSON
refusals were all measured against exactly that source; retyping it would risk
diverging from the thing that was proven. Everything else — transport, snapshot,
entity layer, `Server` — was written fresh against the contract table.

The core publishes with **no** POM dependencies. JSpecify is compile-only, so
its class-retention annotations never become something a consumer resolves.
Each module carries its own `Automatic-Module-Name`.

## Consumer journeys

The same journey test, byte-identical, compiles and runs unchanged under both
build tools against the published artifacts: create a session, add a window,
split it, send keys, capture the pane, refresh the snapshot, select a window
with `Selections.exactlyOne`, filter panes with a typed field, and tear down.

Neither consumer imports anything internal, and neither needs setup knowledge
beyond its own README: a repository URL, a coordinate, and which tmux binary to
use. The Gradle consumer resolves through Gradle module metadata and the Maven
consumer through the POM, so both resolution paths are exercised.

## Ownership

`Server.using` borrows a transport and never closes it, so several servers can
share one and closing a borrower leaves the others dispatching. A closed server
refuses to dispatch rather than reviving. `Server.open` owns the transport it
built and closes it exactly once.

## A leak the integration found

An oracle fixture written for the pushdown bakeoff removed its directory —
including the socket — while its server was still running. Twenty-four servers
across the version matrix were left orphaned, and they were unreachable:
`kill-server` speaks over the socket, so unlinking it first removes the only
supported way to stop the daemon.

This independently confirms the ordering the lifecycle bakeoff had already
settled on, which keeps the directory whenever the daemon is unaccounted for.
It also sharpens the safety rule. The lifecycle note says a pid is never enough
to justify a signal, and that stands — but these processes carried far stronger
evidence: `/proc/<pid>/exe` resolved to a binary that exists only inside this
spike's own tree, and their argv contained this run's unique nonce. Provenance of
that kind, not pid equality, is what makes a signal defensible, and the
reaping filter required both before signalling anything.

The owned-process ledger did not catch them, because that fixture never
registered its servers. A ledger only sees what registers with it, so "the
verifier reported clean" means less than it appears to; the scan that found these
was over process arguments, not over the ledger.

## Gates

Ran, in the integrated tree:

- compilation of every module under `-Xlint:all -Werror` with doclint;
- publication of 15 artifacts — binary, sources, Javadoc, POM and Gradle module
  metadata per module — into an isolated file repository;
- core POM dependency-freedom, and the three automatic module names;
- transport ownership, including refusal after close;
- both consumer journeys against real tmux.

Not run here, and therefore not claimed:

- Spotless, Error Prone and NullAway are not wired into the integrated build;
  they were proven in the build bakeoff and are not re-demonstrated.
- The transport flood, pinning, timeout and interruption gates, the hydration
  differential, the zero-I/O snapshot check and the JSON refusals were not
  re-run against the integrated code. Each passed in its own spike against the
  design this slice implements, but that is not the same as passing here.
- The consumer journeys ran on one tmux lane, not the full matrix.
- Signing, two-copy reproducibility, and the module-path consumer were not
  repeated.
- No independent reviewer has driven the consumers from their READMEs alone.

## Evidence digests

Artifact labels are relative to the disposable root.

| artifact                             | SHA-256                                                            |
| ------------------------------------ | ------------------------------------------------------------------ |
| `artifacts/integration-contracts.md` | `7f075829db236ae87075942cb78ebf8fc58a6a843cabf08b8bcc1d83ef4b816d` |
| `artifacts/integrated-publish.log`   | `6c76ede0af8782b26a4baae0e7b836fdc2703c4d9f0f922449eb23dd9d0b881c` |
| `artifacts/consumer-gradle.log`      | `5aee82ff63ae9be0da26dbe8dfc7d8aaee4ddf8a2a6111e5796287b2c9d6da8f` |
| `artifacts/consumer-maven.log`       | `37ce02d7e0448361f5c8f820b800dbb2a5c05c7c570dfa9a45de60e59de2b752` |
| `artifacts/integrated-results.tsv`   | `5598e94a617634386dd995a329880a29de01de9286c0e2f392ad29cee9bbb69b` |
| `artifacts/integrated-cleanup.log`   | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `artifacts/task-8-evidence.sha256`   | `3fb086a3aa329f0db576e5cde68165ca71402f71792206523f15ea8faae0e3c6` |
