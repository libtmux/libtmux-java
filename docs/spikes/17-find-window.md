# find-window finds nothing

## Verdict

`find-window` opens the window browser narrowed to what matched. It does not
select a window, does not report whether anything matched, and does not fail
when nothing did. Exposed as what it is — a filtered chooser — and named so a
caller does not expect a search.

## Measurement

Three windows (`tmux`, `editor`, `logs`), then `find-window` against a detached
server.

| asked for                      | error | `#{pane_mode}` after | active window |
| ------------------------------ | ----- | -------------------- | ------------- |
| `-N editor` — one match        | none  | `tree-mode`          | `tmux`        |
| `-N e` — two matches           | none  | `tree-mode`          | `tmux`        |
| `-N zzznope` — no match        | none  | `tree-mode`          | `tmux`        |

Identical on 3.2a, 3.6 and 3.7b. The active window is unchanged in every row,
including the one where exactly one window matched — so this is not a jump.

The third row is the one that matters for an API: a match that found nothing
enters the browser just the same. Neither the exit status nor `#{pane_mode}`
distinguishes it from a match that found three, so no wrapper can honestly
answer "did it find anything". A caller who needs that should filter the
windows a capture already holds.

## The flags held still

`CiNrt:TZ` on 3.2a, 3.4, 3.6 and 3.7b alike, so nothing here needs a version
rule. `-N` (name), `-C` (content) and the default (name, title and content) are
exposed; `-i`, `-r` and `-Z` are not, pending a caller who wants them.

## display-menu is not exposed

It shares a source file with `display-popup` and shares its requirement:

```console
$ tmux -S "$sock" display-menu -t base "Item" q "display-message hi"
no current client
```

Every lane. Its flags also moved twice inside the range — 3.4 added `-b -C -H
-s -S`, 3.6 added `-M` — so exposing it would mean a version rule for a command
that cannot run without a terminal. Deferred for the same reason
`new-session -A` was: a library is usually called from something with no tty.

## Commands

```console
$ tmux -S "$sock" -f /dev/null find-window -N -t base editor
```

```console
$ tmux -S "$sock" -f /dev/null display-message -p -t base '#{pane_mode}'
```

## Not covered

- Whether an attached client renders the narrowed tree differently from the
  full one.
- `-r`, which matches by regular expression, and what it does with an invalid
  one.
- Whether `-Z` zooms the pane the browser opens in.
