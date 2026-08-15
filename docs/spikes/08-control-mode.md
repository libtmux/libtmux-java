# Control mode framing and attribution

## Verdict

Use control mode for independent requests, one command per line, and take
attribution from tmux's own reply framing rather than inferring it. Quote every
argument in single quotes, closing and reopening around an embedded one.

## What the framing gives

A control client answers each request with a block:

```
%begin <time> <number> <flags>
…the command's output…
%end <time> <number> <flags>
```

A failure ends the block with `%error` instead of `%end`. The number is tmux's
own correlation, so a reply identifies its request without a client counting
anything.

Measured on 3.7b with three requests, the middle one failing:

| request                        | reply block |
| ------------------------------ | ----------- |
| `display-message -p first`     | `%end`      |
| `select-pane -t =missing`      | `%error`    |
| `display-message -p third`     | `%end`      |

The third request ran. That is the property a semicolon group cannot offer: a
group is discarded after its first failure, so the same three commands leave the
third unexecuted and unattributable. Control mode has no `SKIPPED` outcome
because nothing is skipped.

## Quoting

A request is one line, so tmux's own lexer parses it. Single quotes preserve
everything, and an embedded single quote is closed, escaped and reopened —
`'it'\''s'`. Verified against 3.7b for spaces, an embedded single quote, a
backslash and a semicolon; all four arrive as the argument that was sent.

Without this a semicolon inside an argument becomes a command separator, which
is the same class of defect as the fixed row separator in
[the row framing note](07-row-framing.md).

## No command delays a reply

`run-shell` and `wait-for` both return `%end` immediately. tmux replies as soon
as it queues a command, so no ordinary command makes a reply arrive late, and a
timeout cannot be exercised by asking tmux to be slow.

The unanswered-request path is therefore tested by stopping the server process
with `SIGSTOP`, which makes the reply genuinely never arrive, and by running the
identical request against a running server as the control. Without that control
the test would pass just as well against a client that never worked.

## Not covered

- Only tmux 3.7b was measured. Whether older lanes frame blocks identically
  belongs with the version matrix.
- `%output` decoding handles tmux's three-digit octal escapes; other escape
  forms have not been exercised.
- Notifications other than `%output` are read and discarded. State is read by
  taking a snapshot, not by tracking notifications.
