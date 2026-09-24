# Choosing sessions by what they hold

## Verdict

A pane lookup and a filtered read choose their sessions with **one `-f` filter
that loops each row's session**, and read those sessions in the same fenced call.
Two commands, as a snapshot costs, with no probe first.

```text
#{W:#{P:#{?<pane condition>,1,}}}     a session holding a matching pane
#{W:#{?<window condition>,1,}}        a session holding a matching window
```

`W:` loops the windows of the row's session and `P:` the panes of each, so the
filter is true for every row of a session that holds a match: `list-sessions`,
`list-windows -a`, and `list-panes -a` with the same filter return whole sessions.

## Measured

Every build from 3.2a to master, one server each, two sessions, the target pane
in the second:

| tmux | sessions kept | windows kept | panes kept | a missing pane |
| --- | --- | --- | --- | --- |
| 3.2a, 3.3, 3.3a, 3.4, 3.5, 3.5a, 3.6, 3.6a, 3.6b, 3.7, 3.7a, 3.7b, 3.7c, 3.8-rc, master | the second | its one window | both its panes | no rows |

A window linked into two sessions brings both, as a snapshot would show it twice.

What the loop costs inside tmux, on 3.7c, forty repetitions of the three listings:

| server | full listings | looped filter | bytes returned, full / filtered |
| --- | --- | --- | --- |
| 5 sessions, 20 panes | 3.6 ms | 2.9 ms | 400 / 82 |
| 30 sessions, 300 panes | 33 ms | 26 ms | 6,920 / 215 |
| 100 sessions, 1,000 panes | 45 ms | 17 ms | 24,070 / 245 |

One run on a busy machine put the 1,000-pane filter at 61 ms against 42, so the
loop is not free: each row expands the condition once per pane of its session.
The library's own measurement, which includes parsing the rows, is in
[`docs/benchmarks/operations.md`](../benchmarks/operations.md).

## What it replaced

A probe listed the matching rows' `session_id`, then a second fenced call read
those sessions: three commands for a filter, four for a pane lookup, and a probe
that failed on a server with no sessions had to be told apart from a real
failure by tmux's English error text.

## What checks it

`PushdownIntegrationTest` runs every filter shape both ways, pushed and local,
on every matrix lane, including glob escapes at window and pane level, where the
condition sits one expansion deeper. Dropping the `W:` loop from the window lift
fails it.
