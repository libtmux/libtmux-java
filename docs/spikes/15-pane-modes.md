# Pane modes without a client

## Verdict

Every mode a pane can be put into works on a server nobody is attached to, and
one command leaves all of them. The two commands that draw for a client are the
ones that refuse.

## What works detached

A chooser is something a client renders, so none of this was obvious. tmux sets
the mode on the pane regardless, and a client shows it whenever one arrives.
Measured by reading `#{pane_mode}` afterwards rather than by exit status, which
is the same for every row here.

| command          | `#{pane_mode}` afterwards |
| ---------------- | ------------------------- |
| `copy-mode`      | `copy-mode`               |
| `clock-mode`     | `clock-mode`              |
| `choose-tree`    | `tree-mode`               |
| `customize-mode` | `options-mode`            |
| `choose-buffer`  | *(nothing)*               |
| `choose-client`  | *(nothing)*               |

Identical on 3.2a, 3.6 and 3.7b.

## The two that do nothing are conditional, not unsupported

`choose-buffer` opens once there is a buffer to choose:

| server holds     | `#{pane_mode}` after `choose-buffer` |
| ---------------- | ------------------------------------ |
| no buffers       | *(nothing)*                          |
| one buffer       | `buffer-mode`                        |

tmux declines to show a chooser with nothing in it, and reports that by
succeeding and leaving the pane as it was. `choose-client` behaves the same way
on a server with no client attached. Exit status cannot distinguish "opened"
from "declined"; asking the pane can.

## Leaving is `copy-mode -q`, whatever the mode

| pane was in    | after `copy-mode -q` |
| -------------- | -------------------- |
| `copy-mode`    | *(nothing)*          |
| `clock-mode`   | *(nothing)*          |
| `tree-mode`    | *(nothing)*          |

The name is tmux's history rather than its behaviour — it quits a clock and a
chooser just as readily. `send-keys -X cancel` is not a substitute: it leaves
copy mode, but answers `not in a mode` for a pane showing a clock, because `-X`
dispatches through a client's key table and a clock takes no such commands.

## Two that need a client

| command         | detached                      |
| --------------- | ----------------------------- |
| `display-panes` | `can't find client: <target>` |
| `display-popup` | `no current client`           |

Both on every lane, and `display-popup` refuses with or without a command to
run. These are drawn *for* a client rather than set *on* a pane, which is the
line between the two tables above.

## Commands

```console
$ tmux -S "$sock" -f /dev/null clock-mode -t base
```

```console
$ tmux -S "$sock" -f /dev/null display-message -p -t base '#{pane_mode}'
```

## Not covered

- `choose-tree`'s own flags, which pick what the tree shows and how it is
  sorted.
- Whether a client attaching later renders a mode set while none was attached.
- `display-menu`, which shares a source file with `display-popup`.
