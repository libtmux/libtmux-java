# 0008. Control mode: one command per line, tmux's own reply framing

Status: Accepted

## Context

A control-mode client answers each request with a `%begin <time> <number>
<flags>` / `%end` block, or `%error` in place of `%end` on failure. The number
is tmux's own correlation, so a reply identifies its request without a client
counting anything. Measured with three requests where the middle one fails,
the third still runs and answers its own block — the property a
semicolon-joined group cannot offer, because tmux discards the rest of a group
after its first failure. Control mode has no `SKIPPED` outcome because nothing
is skipped.

A request is one line, so tmux's own lexer parses it, and every argument must
survive that parse. Single quotes preserve everything; an embedded single
quote is closed, escaped, and reopened (`'it'\''s'`), verified against spaces,
an embedded quote, a backslash, and a semicolon. `#` in an unquoted argument
starts a comment to tmux's lexer, which one script rediscovered by sending a
`refresh-client -B` format unquoted and getting `parse error: syntax error`
back — `ControlClient.line` already quoted every request it sent, so the
defect was in the probe, not in the client.

## Decision

Send each control-mode request as one line, quote every argument in single
quotes with embedded-quote escaping, and attribute a reply from tmux's own
`%begin`/`%end`/`%error` framing rather than by inferring completion from
output shape or timing.

## Consequences

A command that must run to completion and report distinctly on failure is a
control-mode candidate; a semicolon-joined group is not, because a mid-group
failure silently drops every command after it. `run-shell` and `wait-for`
answer `%end` immediately once queued, so no ordinary command delays a reply;
only a process actually stopped (or killed) exercises the unanswered-request
path, which is how `ControlClientTest` tests it.
