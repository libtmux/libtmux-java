# 0005. Pushdown lowering is exact or it is refused

Status: Accepted

## Context

`querySessions(expr)` and its siblings mean tmux evaluates the complete
predicate; if that cannot be guaranteed exactly for a given command, server
version, field, and operator, lowering must fail explicitly rather than
approximate. An adversarial differential matrix — every vector evaluated
in-JVM and against real tmux, across the whole supported version range —
found tmux silently disagreeing with a naive translation in several ways:

- A bare literal in a tmux condition is read as a *variable lookup* and
  expands empty, so `not(and())` naively lowered to `#{?1,0,1}` matches every
  row where Java answers false for every row. Lowering must target a
  normalized semantic form capable of representing constants — constant
  folding, associative flattening, and composition shape — rather than
  emitting tmux condition syntax directly from the AST.
- tmux's own format escape character is `#`, not backslash:
  `#{==:a#,b,a#,b}` is `1` while the backslash form is `0`. A literal operand
  must be escaped for `fnmatch` first, then for the format, or user data
  reaches the wire unescaped.
- `#{<:2,10}` orders lexically, not arithmetically; a glob pattern such as
  `#{m:foo**,foobar}` lets user data occupy glob syntax and over-match; a
  right-nested `Or` breaks tmux's own parser between 99 and 100 operands; and
  a field valued `00` is truthy, since only `""` and `"0"` are falsy.
- Filter capability is per command, not per version: `list-clients -f` rejects
  a filter before tmux 3.4 while `list-sessions`, `list-windows -a`, and
  `list-panes -a` accept one throughout the supported range.

None of this reaches a relation. `TmuxFilters` refuses to compile a `ToMany`
or `ToOne` node, a Java regular expression, or any operand containing a
character with format meaning (`,` `#` `{` `}` `:` or a backslash) — those
stay a local filter evaluated over a capture instead.

A separate, narrower need exists inside the library itself: choosing which
sessions hold a given pane or window, without listing everything first. tmux
evaluates a nested loop inside one `-f` expression identically on every
released lane:

```text
#{W:#{P:#{?<pane condition>,1,}}}     a session holding a matching pane
#{W:#{?<window condition>,1,}}        a session holding a matching window
```

`W:` loops the windows of the row's session and `P:` the panes of each, so the
filter is true for every row of a session that holds a match, and
`list-sessions`, `list-windows -a`, and `list-panes -a` given the same filter
return whole sessions. This costs more than a full listing at small scale but
less at scale (measured: 1,000 panes, 45 ms full versus 17 ms filtered), and
it is a fixed, internal lowering — not something a caller's arbitrary
`FilterExpr` reaches through `TmuxFilters`.

## Decision

`TmuxFilters.format` returns a tmux `-f` string only when every node in the
expression is one tmux can evaluate exactly, through a normalized form that
folds constants before rendering; anything else — a relation, a Java regular
expression, or an operand tmux's format syntax would reinterpret — is refused
and evaluated locally over a full capture instead. `SnapshotCapture` uses its
own fixed `W:`/`P:` loop lowering, separately, only for session-scoped
existence lookups it controls end to end.

## Consequences

A caller's filter either runs exactly as tmux would evaluate it or is
evaluated locally in Java; there is no third, approximate outcome.
`PushdownIntegrationTest` runs every filter shape both pushed and local across
the matrix and requires identical results. Adding a pushdown-eligible operator
means adding it to the normalized form and to the differential matrix, not
just to the renderer.
