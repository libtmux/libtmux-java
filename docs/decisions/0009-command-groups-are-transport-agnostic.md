# 0009. A command group is read literally and encoded per carrier

Status: Accepted

## Context

tmux ends a command at a semicolon ending *any* argument, not only at one
standing alone, and a backslash immediately before it keeps the semicolon
instead (`cmd_parse_from_arguments` in `cmd-parse.y`). Reading the rule as "a
semicolon standing alone separates two commands" is wrong, and the two
carriers disagreed about grouping as a result. Three divergences were measured
against tmux 3.7b, sending the same argv both ways: a window name ending in an
escaped semicolon ran as a *shell command* under one carrier and as a literal
name under the other, silently leaving a stray window behind; a two-command
semicolon group produced two separate control-mode reply blocks for a client
awaiting one request, so the extra block was silently matched to whatever
asked next; and a trailing escaped semicolon decoded differently depending on
which carrier's own argv parser saw it first.

The earlier design made escaping tmux's group-ending rule a caller obligation,
which only `Buffers` actually met — every other path carrying caller-supplied
text silently lost a trailing semicolon.

## Decision

`CommandRequest` holds a command's arguments literally; no carrier receives a
value some other layer has already escaped for it. `ControlClient.isCommandGroup`
is the single reading of tmux's actual rule (a semicolon ending an argument,
unless escaped by a preceding backslash), and both carriers consult it before
sending. `ControlTransport` routes a detected group over a process carrier,
matching how it already handles a command that runs something of tmux's own.
`ControlClient.send` refuses a group outright rather than accepting a request
it can only partially answer, and `ControlClient.line` spends the backslash
(tmux's own argv-parser escape) rather than quoting it, because control mode's
argv parsing is what this path actually goes through.

## Consequences

Each carrier is responsible for encoding a literal argument correctly for its
own parser; no shared pre-escaping step exists to get out of step with either
one. A caller composing untrusted text into a command argument gets the
correct grouping behavior regardless of which carrier eventually sends it. See
[SECURITY.md](../../SECURITY.md) for what this means for text a caller did not
author.
