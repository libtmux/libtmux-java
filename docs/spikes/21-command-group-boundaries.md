# Where one tmux command ends and the next begins

## Verdict

The carriers disagreed about it, which makes it the first real breach of the
claim `ExecutionModeConformanceTest` exists to hold: the same argv changed the
server differently depending on how it travelled. Three measurements below, all
against tmux 3.7b, and all of them now gated.

The cause was reading tmux's rule as "a semicolon standing alone separates two
commands". tmux's actual rule is that a semicolon **ending any argument** ends
the command, and a backslash before it keeps the semicolon instead.

## What tmux does

`cmd_parse_from_arguments` in `cmd-parse.y` is the whole rule, and it runs
before any command does:

```c
if (size != 0 && copy[size - 1] == ';') {
        copy[--size] = '\0';
        if (size > 0 && copy[size - 1] == '\\')
                copy[size - 1] = ';';
        else
                end = 1;
}
```

Measured rather than inferred, one argv element per row:

| element        | tmux reads it as                        |
| -------------- | --------------------------------------- |
| `plain`        | the argument `plain`                    |
| `semi;colon`   | the argument `semi;colon`               |
| `;leading`     | the argument `;leading`                 |
| `x;`           | the argument `x`, then a second command |
| `trailing\;`   | the argument `trailing;`                |

Only a semicolon in final position is a separator. Only a backslash in the
position before it is an escape.

## Three divergences

Each row is one argv, sent both ways to a server in the same state.

| argv                                              | process carrier                       | control carrier, before        |
| ------------------------------------------------- | ------------------------------------- | ------------------------------ |
| `new-window -d -n 'grouped;' list-windows -F …`    | window `grouped`, then a listing      | one command; window `grouped;` running the listing as its shell command, gone by the next capture |
| `list-sessions … ; list-windows …`                 | two commands, two answers             | two `%begin`/`%end` blocks, one awaited request |
| `display-message -p 'trailing\;'`                  | `trailing;`                           | `trailing\;`                   |

The first is the worst, because it is not a failure. The process carrier leaves
a window behind and the control carrier leaves none, and nothing reports that
anything differed.

The second is worse still in a way that does not show up here. Control mode
frames a reply per command, so two commands produce two blocks while the client
is waiting on one request. The extra block is matched to whatever asks next:

```
%begin 1786801198 289 1
spike
%end 1786801198 289 1
%begin 1786801198 290 1
zsh
%end 1786801198 290 1
```

Both blocks arrive from a single written line. A client that polls one pending
request per `%end` hands the second block to the following request, which then
returns a window listing as its own answer. Silent, and it corrupts every reply
after it rather than the one that caused it.

## What changed

`ControlClient.isCommandGroup` is now the single reading of tmux's rule, and
both carriers consult it:

- `ControlTransport` routes a group over a process, as it already did for the
  commands that make tmux run something of its own.
- `ControlClient.send` refuses one outright. It promises one reply and a group
  has several, so the honest answer is to decline before writing anything —
  which also leaves the stream in step.
- `ControlClient.line` spends the backslash rather than quoting it. It exists
  for tmux's argv parser, which the process carrier goes through and this one
  does not.

## Why the gate did not catch it

`ExecutionModeConformanceTest` compares carriers across a trajectory of eight
scenarios, and none of them contained a semicolon. The gate was sound; its
inputs did not reach the case. Two scenarios now do, and they fail against the
previous code for the reasons in the table above.

## Not covered

- A doubled backslash before a trailing semicolon. tmux inspects one character,
  so `a\\;` becomes `a\;`, and this implementation agrees by construction — but
  it was reasoned from the source rather than measured, so no test asserts it.
- Whether any tmux before 3.7b reads the rule differently. `cmd-parse.y` has
  carried this form across the supported range, and the matrix runs the new
  scenarios, but no release-by-release measurement was taken.
