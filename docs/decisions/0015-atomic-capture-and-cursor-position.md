# 0015. Capture and cursor position travel in one invocation

Status: Accepted

## Context

Where a line sits in a pane's capture depends on how far the pane has
scrolled, so a capture and a separate `#{history_size}` read can describe
different moments. Measured against continuous output: three separate
invocations disagreed about position 40 times out of 60, while one batched
invocation disagreed 0 times out of 60. tmux does not process pane output
between two commands sent in the same invocation, so a batched pair cannot be
torn; two invocations under continuous output are torn roughly two times in
three, a window wide enough that it reached CI rather than a local run.

A terminal is a grid, not a log: a row that has been delivered can still be
rewritten, and the row the cursor currently sits on is being drawn. A capture
can catch a partially drawn row and a later read then finds that same anchor
changed, reporting a discontinuity that never actually happened — reproduced
by a shell redrawing a wrapped command line as the pane scrolled. Anchoring
only to lines the terminal's own cursor has moved past avoids handing a caller
half of a line as though it were the whole of one.

## Decision

Read a pane's capture and its cursor position in one tmux invocation, batched
rather than sequential (`libtmux-mcp`'s `Screen`). Anchor a resumable cursor
only to lines above the terminal cursor's own row; a full-screen capture still
returns the whole visible screen, cursor row included, but only finished lines
are eligible as a resumable anchor (`Cursor`).

## Consequences

A caller of the MCP wait and capture tools cannot construct a torn read by
racing a capture against a position query, because there is no code path that
issues them separately. Resuming from a cursor never hands back a line that
might still be rewritten; a pane that was cleared or whose history rolled past
the anchor is reported as discontinuous rather than silently stitched to
unrelated lines.
