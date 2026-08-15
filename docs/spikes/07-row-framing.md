# Row framing for listing formats

## Verdict

Split listing rows on a separator generated once per process, not on a fixed
character. A fixed separator is breakable by any name a user is free to choose,
and tmux accepts such a name without complaint.

## The hole in a fixed separator

Python libtmux frames fields with U+241E, and carrying that over looked free.
It is not: the character has no special meaning to tmux, so it survives into a
window name and then into the row.

A window renamed to `win␞name`, listed with a three-field U+241E template:

| template fields | fields the splitter saw |
| --------------: | ----------------------: |
|               3 |                       4 |

The extra field is not detectable after the fact. A row with the right number
of fields but a name that happens to contain the separator shifts every
following field by one, so a pane id can be read as a name.

Replacing the separator with a per-process random token and repeating the same
listing returns three fields for the same hostile name. The caller would have to
name a window with the exact token this process generated.

## Commands

```console
$ tmux -S "$socket" -f /dev/null new-session -d -s alpha
```

```console
$ tmux -S "$socket" rename-window "$(printf 'win␞name')"
```

```console
$ tmux -S "$socket" list-windows -a \
    -F "$(printf '#{session_id}␞#{window_id}␞#{window_name}')" \
    | awk -F"$(printf '␞')" '{print NF}'
```

## Observed, not resolved

A window created as `inject#{session_id}here` reads back as `inject$0here`, and
one renamed to `lit#{session_id}eral` reads back as `lit$0eral`. So a format
sequence inside a name is expanded somewhere between the rename and the listing.

This probe cannot say which side does it. `#{q:window_name}` returns
`lit\$0eral` under both explanations — expansion at write time leaves `$0`
stored and `q:` escapes the `$`, and expansion at read time produces the same
two characters before `q:` sees them. Distinguishing them needs a path that
stores a name without going through format expansion.

It does not affect framing either way, and the same behavior is observable from
Python libtmux, so it is recorded rather than acted on.

## Not covered

- Only tmux 3.7b was probed. Whether older lanes accept a separator character in
  a name identically belongs with the version matrix.
- Session and pane names were not probed; the window case is enough to reject a
  fixed separator.
