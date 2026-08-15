# run-shell loses its output in the middle of the range

## Verdict

Reading what `run-shell` printed works on 3.2a, stops working in 3.3a and 3.4,
and works again from 3.5. Running the command for its effect works throughout.
Two methods, because one of those is a hole and the other is not.

## Measurement

Six attempts per lane, asking whether `run-shell 'echo captured-me'` reported
anything back.

| lane   | captured |
| ------ | -------- |
| `3.2a` | 6/6      |
| `3.3a` | **0/6**  |
| `3.4`  | **0/6**  |
| `3.5`  | 6/6      |
| `3.6`  | 6/6      |
| `3.7`  | 6/6      |
| `3.7a` | 6/6      |
| `3.7b` | 6/6      |

Never intermittent: every attempt on a lane agreed with every other. The exit
status is right throughout — `run-shell 'exit 3'` returns 3 on every lane — and
so is the command's effect. Only the reporting goes missing, and only in the
two releases.

## Why it is not a floor

Almost every version rule in this library is "released in X, absent before".
This one is a gap with working releases on both sides, so a message reading
"requires tmux 3.5" would be false for the 3.2a user it works for.
`UnsupportedTmuxVersion` gained a form that states the range instead.

A rule written as `atLeast(3.5)` would also have been wrong in the other
direction: it would refuse 3.2a, where the capability is present.

## Why two methods

`run-shell` does two things a caller might want, and the two releases break only
one of them:

- `runShell` runs the command. Works everywhere, claims nothing about output.
- `runShellCapturing` runs it and answers with what it printed. Refuses on 3.3a
  and 3.4 rather than answering with an empty list, which is what tmux does
  there and is indistinguishable from a command that printed nothing.

Collapsing them into one method would have meant either denying 3.3a and 3.4 a
working capability, or handing back silence that looks like an answer.

## The flags moved too

`run-shell` gained flags twice inside the supported range, which is worth knowing
before any of them is exposed:

| lane   | args           | gained            |
| ------ | -------------- | ----------------- |
| `3.2a` | `bd:Ct:`       |                   |
| `3.4`  | `bd:Ct:c:`     | `-c` start dir    |
| `3.6`  | `bd:Ct:Es:c:`  | `-E`, `-s`        |
| `3.7b` | `bd:Ct:Es:c:`  |                   |

None of those is exposed yet. `-c` would need a 3.4 floor and `-E`/`-s` a 3.6
one.

## Commands

```console
$ tmux -S "$sock" -f /dev/null run-shell 'echo captured-me'
```

## Not covered

- Whether `-b`, which backgrounds the command, reports output on any lane.
- `if-shell`, whose flags did not move (`bFt:` throughout).
- Where the output goes on 3.3a and 3.4 — only that it does not come back.
