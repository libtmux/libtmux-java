# Telling a command's output from the shell's echo of it

## Verdict

Frame the command with a random nonce and cut on whole-line equality. The shell's
echo of the payload *contains* both markers; no echo is ever *equal* to one.

Do not try to recognise the plumbing by its shape. `capture-pane -J` does not
rejoin the rows it would have to.

## The problem

`tmux_run` types this into a pane's own shell, so it can know when the command
finished and what it exited with:

```
 echo <nonce>-s; ( <command> ); <nonce>=$?; echo <nonce>-e; tmux … set-option … \; wait-for -S …
```

The shell echoes all of it. Everything after that line is the command's real
output, and something has to say where the plumbing ends.

## Why matching the plumbing fails

`-J` is documented as joining wrapped lines, and it does — for the rows tmux
marked as wrapped. Measured in a 40-column pane, tmux 3.7b:

| rows | drawn by | `-J` rejoins them |
| ---- | -------- | ----------------- |
| the literal `send-keys` echo | the terminal wrapping | yes, into one line |
| the shell's redraw at the prompt | zsh, with cursor movement | **no** |

So the channel name appeared on two lines even with `-J`, split across rows in
the second case. Recognising the plumbing textually means handling that split —
which is the "hex continuation" problem the sibling Python port carries about
sixty lines of regular expressions for.

## Why framing works

A marker line is short and printed by `echo`, so it never wraps. The echo of the
payload is long and holds the marker as a substring. Equality after trimming
separates them with no regular expressions at all.

Measured, 40 columns, a multi-line failing command:

```
1: … ❯  echo lx7f3a91c2-s; ( printf "alpha\nbeta\n"; ls /definitely-not-here;
2:  printf "omega\n"; exit 3 ); s=$?; echo
3: lx7f3a91c2-e; tmux -S … set-option -p @st_lx7f3a91c2 "$s"; tmux -S …
4: … wait-for -S ch_lx7f3a91c2
5: lx7f3a91c2-s
6: alpha
7: beta
8: ls: cannot access '/definitely-not-here': No such file or directory
9: omega
10: lx7f3a91c2-e
11: … ❯
```

Two lines *contain* the start marker; exactly one *equals* it. Framing returned
lines 6 to 9 — stdout and stderr, no plumbing — and the pane option carried
`3`.

## What the frame costs

The echo is what a person watching the pane sees, so it is kept as short as
correctness allows:

- the two tmux commands are sent as **one** invocation with a `;` argument
  separating them, halving the invocations
- the config-file flag is left off, because it is read when a server starts and
  means nothing to a command sent to one already running

## Consequences that had to be pinned

- The command runs in a **subshell**. A `cd` or an export does not outlive the
  call — and neither does `exit`, which is what stops `exit 3` closing the pane.
- The status variable is named for the nonce, so it cannot collide with one the
  person using the pane already had.
- `tmux_run` returns on the completion signal, which fires **before** the shell
  redraws its prompt. A following `capture_since` reports that prompt as new
  output, correctly.

## When the frame is lost

Output longer than the pane's history pushes the start marker out of reach. The
answer then reports `framed: false` and says the output may include the command
line itself, rather than pretending it was cut exactly.
