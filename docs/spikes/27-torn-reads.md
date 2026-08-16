# Reading a pane that is still being written

## Verdict

Take the capture and the pane's position in **one tmux invocation**, and anchor a
cursor only to lines the terminal's own cursor has moved past.

Either alone is not enough. Both were measured against the same reproduction, and
each one on its own still reports a pane that merely scrolled as a pane that was
cleared.

## Why one invocation

Where a line sits in a capture depends on how far the pane has scrolled, so a
capture and a `#{history_size}` read taken separately can describe different
moments. Reading the position either side of a capture says how often:

| how the three reads were made | disagreed about the position |
| ----------------------------- | ---------------------------: |
| one invocation (`Batch`)      | **0 of 60** |
| three invocations             | 40 of 60 |

tmux does not process pane output between two commands of the same invocation, so
a batched pair cannot be torn. Two invocations under continuous output are torn
two times in three — which is why this reached CI rather than a local run: the
window is wide, and only the timing of what lands in it varies.

## Why the cursor's own row is excluded

A terminal is a grid, not a log. A row that has been delivered can be rewritten,
and the row the cursor sits on is still being drawn — a capture catches
`line-123` as `line-12`, and the next read then finds the anchor changed and
reports a discontinuity that never happened.

Caught here as a shell redrawing a wrapped command line. The anchor was:

```
(seq 1 300); do for i in $(seq 1 50); do echo line-$r-$i; done; sleep 0.01; done
```

— a continuation row of an echoed command, which the shell redrew as the pane
scrolled.

So `#{cursor_y}` comes back in the same invocation, and only lines above the
cursor's row are delivered or anchored to. A line still being written is held
until it is finished, which also stops half a line being handed to a model as
though it were the whole of one.

`tmux_capture_pane` still shows the whole screen, cursor row included — someone
asking what a pane shows wants what it shows, including a full-screen program's
last line. Only the cursor it hands back is limited to finished lines.

## The reproduction

A ten-row pane, a paced producer, and sixty reads:

```
for r in $(seq 1 200); do for i in $(seq 1 30); do echo line-$r-$i; done; sleep 0.01; done
```

About 3,000 lines a second: fast enough that lines land inside the gap between
two invocations, slow enough that the history never rolls past what a cursor
points at. The pane's `history-limit` is raised first, so a real overrun cannot
be mistaken for the fault under test.

Both fixes are load-bearing, and the guard was checked against each:

| what was in place | the guard |
| ----------------- | --------- |
| neither | fails |
| finished-lines only, two invocations | fails |
| batched, both | passes, three runs |

## What the rate has to be

Getting this reproduction wrong in either direction is easy, and both mistakes
look like a passing test:

- **Too slow** — 50 lines a second never lands inside the gap, and the guard
  passes against the very bug it exists to catch.
- **Too fast** — an unpaced `seq` into a 2,000-line history overruns it between
  reads, and the discontinuity it reports is *correct*, so the guard fails
  against code that is right.

## What it cost to find

Two tests failed in CI on a tag that had passed `check --rerun-tasks` twice
locally. Nothing was published, because the release workflow runs the same gate
before it uploads anything. The local runs were green because a quiet machine
rarely lands output inside a two-millisecond window; the CI runner is slower and
its prompt is longer, so it does.
