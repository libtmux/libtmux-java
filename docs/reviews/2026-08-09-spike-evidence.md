# Spike evidence review

> **Superseded in part, 2026-08-15.** This audit was written before the clean
> rewrite. Its closing section lists the rewrite and the two consumer packages as
> not yet begun; all three have since landed, and this repository is now the
> port's only home rather than a directory inside the Python library's tree. The
> defects and the rules carried out of them still stand, and the rest of the
> document is left as the record it was.

An audit of what the six spike notes claim against what can still be verified.
It reports two defects in the evidence discipline itself, both of which make a
citation resolve to the wrong thing rather than fail loudly.

## Verified

Every durable claim's supporting artifact was re-hashed where the artifact still
exists: 48 digests match. No prototype source, build output, recording, socket,
or local path was ever committed; the repository holds sixteen Markdown files
and nothing else under `java/`.

Two independent cleanliness scans agree that nothing survives: no process
anywhere on the host references the prototype tree, and the owned-process ledger
verifier reports no live registered process.

## Defect: artifact paths were not scoped to the task that cited them

`artifacts/process-cleanup.log` is cited by two notes written in two different
prototype trees. The first tree was removed by the host's temporary-file cleaner
partway through the work, so the artifact that note cites no longer exists — but
a later task wrote a file of the same name into the replacement tree. The
citation therefore still resolves, to unrelated content, and its digest no longer
matches.

That is a worse failure than a dangling reference. A missing artifact announces
itself; a reused path silently substitutes different evidence under the same
name, and only a digest comparison notices.

`artifacts/hydration-counts/synthesis.tsv` fails the same way for a different
reason: it is written in append mode, and later runs of the same suite grew it
from the eight rows the note cites to thirty-two. The digest was correct when
written and is not now.

**The rule that should have applied.** An artifact path must be unique to the
task and run that cites it, and an append-mode file must be snapshotted at
citation time rather than cited in place. Neither note is wrong about what it
measured; both are unverifiable today, which for evidence is the same as wrong.

Forty-three further artifacts cited by the build-and-coordinates note are simply
gone with the reaped tree. That loss is understood and was recorded when it
happened, but it means that note's claims now rest on the commit that recorded
them rather than on anything re-checkable.

## Defect: the owned-process ledger only sees what registers with it

During integration, twenty-four tmux servers were found alive inside the
prototype tree while the ledger verifier reported clean. The fixture that started
them never registered them, and a ledger cannot report what it was never told.

The scan that found them read process arguments, not the ledger. Both checks are
now run, and the argv scan is the one that decides, because it does not depend on
the code under test having cooperated.

The same incident produced two findings recorded in the integration note: that
unlinking a socket before proving the daemon exited orphans it permanently, and
that what makes a signal defensible is provenance — an executable path and an
argv nonce belonging to this tree — rather than a pid comparison.

## What the notes claim, and how strongly

| note                  | strongest claim                                             | re-verifiable today   |
| --------------------- | ----------------------------------------------------------- | --------------------- |
| build and coordinates | topology, coordinates, publication and consumer journeys    | no; tree was reaped   |
| transport             | carrier starvation decides; the pin recording cannot        | yes                   |
| hydration             | four constant commands, eight lanes, linked-window identity | yes, one digest stale |
| query metamodel       | typed handles, pushdown gate, JSON form, no generator       | yes                   |
| JUnit lifecycle       | store ownership plus explicit release; never signal a pid   | yes                   |
| integrated synthesis  | three modules published, two consumer journeys              | yes                   |

## Closure against the plan

The plan's nine tasks are recorded, with the last two qualified. The integrated
synthesis is explicitly partial: the static-analysis stack is not wired into the
integrated build, and the transport, hydration, snapshot-purity and JSON gates
were not re-run against the integrated code. Task 9's remaining step, deleting
the prototype tree, is deliberately left for an explicit decision rather than
taken as a consequence of finishing the audit.

Not yet begun, and not claimed anywhere: the clean rewrite from an empty source
tree, and the two consumer packages — an MCP server and a tmuxp
`WorkspaceBuilder` — that exist to pressure-test the API.

## Carried into the rewrite

- Artifact paths carry their task and run; append-mode files are snapshotted
  before they are cited.
- A cleanliness claim is made from process arguments, not from a registry the
  code under test must remember to update.
- Every gate ships with a control proving it can fail. Three defects this
  project found were gates that could not: a pinning recording blind to the
  pins that mattered, a lifecycle guard that had never seen a failing test, and
  a compile-fail probe whose control could not compile.
