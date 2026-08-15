# split-window flags across the supported range

## Verdict

Carry one size, spelled `-l`, and never emit `-p`. Treat empty, keep, message
and the three styles as one 3.7-only group. Refuse a command alongside empty
before tmux is asked.

## Support by lane

Every flag was checked by observing what it did, not by reading the exit status.
A flag that parses and is ignored returns success.

| flag                    | 3.2a | 3.3a | 3.4 | 3.5 | 3.6 | 3.7 | 3.7a | 3.7b |
| ----------------------- | ---- | ---- | --- | --- | --- | --- | ---- | ---- |
| `-v` `-h` `-b`          | yes  | yes  | yes | yes | yes | yes | yes  | yes  |
| `-l` cells              | yes  | yes  | yes | yes | yes | yes | yes  | yes  |
| `-l` percentage         | yes  | yes  | yes | yes | yes | yes | yes  | yes  |
| `-p`                    | yes  | yes  | **no** | yes | yes | yes | yes  | yes  |
| `-f` `-Z` `-c` `-e`     | yes  | yes  | yes | yes | yes | yes | yes  | yes  |
| `-E` `-k` `-m`          | no   | no   | no  | no  | no  | yes | yes  | yes  |
| `-s` `-S` `-R`          | no   | no   | no  | no  | no  | yes | yes  | yes  |

## `-p` is broken in 3.4

Not a missing feature — a typo upstream. 3.4 declares `p:` and then reads the
wrong flag out of the args:

```c
} else if (args_has(args, 'p')) {
        size = args_strtonum_and_expand(args, 'l', 0, 100, item, &cause);
```

With only `-p` given there is no `-l` to find, so the lookup fails and the
command exits with `size missing`. [3.5 reads `'p'`
here](https://github.com/tmux/tmux/blob/3.5/cmd-split-window.c#L97-L98) and
works.

`-l 25%` gets the same pane on every lane, including 3.2a. So a single size
value with two spellings — cells and percentage — covers the whole range, and
the percentage is a formatting concern rather than a second field. Nothing needs
to reject "both size and percentage", because there is no way to say both.

## Empty and a command exclude each other

3.7 rejects the pair outright:

```console
$ tmux split-window -d -E -t base "sleep 60"
command cannot be given for empty pane
```

The check is [in the exec
path](https://github.com/tmux/tmux/blob/3.7b/cmd-split-window.c#L107-L114),
before anything is spawned, so it fails cleanly. It still belongs in the type
system: a caller should not be able to write the pair down.

## An empty pane still reports a command

`-E` produces a pane whose `pane_pid` is `0`, but whose `pane_current_command`
reads the same as an ordinary pane's:

| split          | `pane_pid` | `pane_current_command` |
| -------------- | ---------- | ---------------------- |
| plain          | real pid   | `zsh`                  |
| `-E`           | `0`        | `zsh`                  |

The obvious field is the one that lies. Anything deciding whether a pane is
running something has to read the pid.

## Two shapes of refusal

An unsupported flag fails differently either side of 3.3:

| lane        | refusing `-E`                                 |
| ----------- | --------------------------------------------- |
| `3.2a`      | `tmux: unknown option -- E` plus a usage line |
| `3.3a`–`3.7b` | `command split-window: unknown flag -E`     |

Neither is worth parsing. It is the reason a version-gated flag is decided
before dispatch rather than recovered from afterwards.

## Reading `-c` and `-e` back

Both work on every lane. The first sweep said otherwise because it read
`pane_current_path` out of the creating command's own `-P -F` output, which is
answered before the pane's shell exists. The value settles a moment later. Any
attribute of a just-created pane has to come from a later read.

## Commands

```console
$ tmux -S "$socket" -f /dev/null split-window -d -l 25% -t base -P -F '#{pane_height}'
```

```console
$ tmux -S "$socket" -f /dev/null split-window -d -E -t base -P -F '#{pane_pid}'
```

## Not covered

- `-I`, which implies empty and then reads the pane's input from the command
  queue. Not exposed by the Python sibling either.
- `new-pane`, whose flags are a superset (`-L`, `-x`, `-X`, `-y`, `-Y`) and
  which floats by default.
- Whether `-s` survives a later `window-style` change on the same pane.
