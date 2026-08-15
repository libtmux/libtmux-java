# break-pane naming on tmux 3.7

## Verdict

Always hand `break-pane` a window name, and on tmux 3.7 exactly rename the
window afterwards when the caller asked for a particular one.

tmux 3.7 gets this wrong twice, in opposite directions, and both are silent
from a client's point of view.

## What 3.7 does

Measured on every released lane, splitting a window and breaking the second pane
out:

| lane   | `break-pane -d -s <pane>` | `break-pane -d -n distinctive -s <pane>` |
| ------ | ------------------------- | ---------------------------------------- |
| `3.2a` | window named `zsh`        | window named `distinctive`               |
| `3.6`  | window named `zsh`        | window named `distinctive`               |
| `3.7`  | **server exits**          | window named `zsh`                        |
| `3.7a` | window named `zsh`        | window named `distinctive`               |
| `3.7b` | window named `zsh`        | window named `distinctive`               |

The first column's failure is not an error return. The server process exits, the
socket goes stale, and every session on it is gone — including sessions the
program never created. The client sees `server exited unexpectedly`.

The second column's failure returns success and a window. It simply is not the
window the caller asked for.

`-P -F` is not involved: the crash reproduces with `-d` alone, and does not
reproduce with `-n` alone. What decides it is whether tmux has to derive the
name itself.

## The workaround

A name is always supplied, so 3.7 never derives one. When no name was asked for,
the name supplied is `#{pane_current_command}` — which every healthy lane picks
by itself, so the result is identical everywhere. When a name was asked for, 3.7
takes it, discards it, and the window is renamed afterwards.

## A probe that proved nothing

The first verification passed a name equal to what tmux would have chosen
anyway. On 3.7 that cannot distinguish "used the name" from "ignored the name",
and it reported the workaround as sound. The name-ignoring half was only found
because the whole suite runs on every lane and an assertion used a name tmux
would never pick.

A probe whose expected and unexpected outcomes look the same is not a probe.

## Commands

```console
$ tmux -S "$socket" -f /dev/null new-session -d -s base
```

```console
$ tmux -S "$socket" break-pane -d -n distinctive \
    -s "$(tmux -S "$socket" list-panes -t base -F '#{pane_id}' | tail -1)"
```

## Not covered

- Whether the crash has other triggers in 3.7 beyond automatic naming.
- tmux versions between releases; only released lanes were measured.
