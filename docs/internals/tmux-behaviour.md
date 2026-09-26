# tmux behaviour this library codes around

Facts about tmux's own commands, measured against the supported version range
(tmux 3.2a through 3.7c unless a row says otherwise), that the implementation
relies on and that are not self-evident from the tmux manual. A decision that
weighs alternatives belongs in [`docs/decisions/`](../decisions/) instead;
this page is measurements, kept reachable because source comments cite them.

## break-pane on tmux 3.7

`break-pane` gets automatic window naming wrong in two opposite, silent ways,
on 3.7 only:

| invocation | tmux 3.7 result | every other lane |
| --- | --- | --- |
| `break-pane -d -s <pane>` (no `-n`) | server exits; every session on the socket is gone | window named from `#{pane_current_command}` |
| `break-pane -d -n distinctive -s <pane>` | success, but the window is named `zsh`, not `distinctive` | window named `distinctive` |

The workaround is to always pass `-n`. When no name was requested, the name
supplied is `#{pane_current_command}` — what every healthy lane picks by
itself — and when a name was requested on 3.7, the window is renamed
afterwards, since tmux takes the name, discards it, and reports success
anyway. A verification that supplies a name equal to what tmux would have
chosen anyway cannot detect the second failure mode.

## split-window flags across releases

| flag | 3.2a–3.3 | 3.4 | 3.5–3.6 | 3.7+ |
| --- | --- | --- | --- | --- |
| `-v -h -b -l -f -Z -c -e` | yes | yes | yes | yes |
| `-p` (percentage) | yes | **broken** (`size missing`, an upstream typo reading the wrong flag) | yes | yes |
| `-E -k -m -s -S -R` | no | no | no | yes |

Only `-l` (accepting a cell count or a `N%` percentage) is exposed; `-p` is
never emitted, both because of the 3.4 defect and because `-l` already covers
percentages on every lane. Empty (`-E`) and a command are mutually exclusive —
tmux rejects the pair outright, `command cannot be given for empty pane` — and
that exclusivity is encoded in the type of `SplitSpec` rather than left to a
runtime check. An empty pane still reports a `pane_current_command` matching
what an ordinary pane would run; only `pane_pid` (`0` for an empty pane) tells
the two apart. `pane_current_path`, read from the creating command's own
`-P -F` output, answers before the pane's shell exists and is stale; any
attribute of a just-created pane must come from a later read.

## new-window and new-session across releases

`new-window` and `new-session` have not gained or lost a flag since 3.2a — the
version-gated concerns are behavioral, not syntactic:

- **3.2a resolves a relative `-c` against the server's working directory.**
  The path reaches the child unchanged, so it lands wherever the server was
  started, or in `$HOME` when that directory has no such path, exit `0`
  either way. 3.3 onwards resolves it against the requesting client's
  directory. `new-window`, `split-window`, `new-session` and `respawn-pane`
  share the spawn code, so all four do this. An absolute directory is honored
  on every release, and every directory is sent absolute, resolved against the
  calling process, so each release starts the process where 3.3 does.
- **3.2a ignores `new-session -x/-y` (sizing)**, silently and with exit `0`.
  It is refused before dispatch rather than accepted and ignored.
- **`new-window -S` (reuse-if-named) reports nothing.** It correctly selects
  the existing window rather than making a second one, but its `-P -F`
  template expands to the empty string on every lane. The window has to be
  found by a follow-up lookup rather than trusted from the creating command's
  own report.
- **`new-session -A` (attach-if-exists) is not exposed.** It performs an
  attach, and an attach needs a terminal (`open terminal failed: not a
  terminal` under `-d`, on every lane). Checking whether the session exists
  and creating it if not works from any process, with or without a tty.

## Pane modes without a client

Every mode a pane can be put into (`copy-mode`, `clock-mode`, `choose-tree`,
`customize-mode`) sets `#{pane_mode}` and works with no client attached; a
client only renders it once one arrives. `choose-buffer` and `choose-client`
report no mode change when there is nothing to choose (no buffers, or no
attached client) rather than failing — exit status cannot tell "opened" from
"declined", only `#{pane_mode}` can. `copy-mode -q` leaves any of these modes,
including a clock; `send-keys -X cancel` does not, because `-X` dispatches
through a client's key table and a clock takes no such commands.
`display-panes` and `display-popup` require an attached client on every lane
and are not exposed for that reason, the same as `new-session -A` above.

## run-shell output is missing on 3.3a and 3.4 only

Reading what `run-shell` printed works on 3.2a, silently reports nothing on
3.3a and 3.4, and works again from 3.5 onward — never intermittent, and the
exit status and the command's real effect are correct throughout. This is a
gap with working releases on both sides, not a floor, so the library exposes
two methods: one that runs a command for effect (works everywhere) and one
that runs it and returns its output (refuses on 3.3a and 3.4, rather than
returning an empty list that would be indistinguishable from a command that
printed nothing there).

## find-window is a chooser, not a search

`find-window` opens the window browser narrowed to what matched; it does not
select a window, and a query that matches nothing still opens the (empty)
browser with no error and no distinguishing exit status. It is exposed as a
filtered chooser, not as a search, so a caller who needs to know whether
anything matched filters a capture instead.

## select-layout can crash the server, on 3.3a only

`select-layout` given a string tmux cannot parse — an unknown built-in name or
a malformed serialized layout — returns an error on every lane except 3.3a,
where the server itself exits, taking every session on that socket with it.
Both the built-in names (checked against an enum) and a serialized layout
string (checked by recomputing tmux's own rotate-and-add checksum over the
layout body, four hex digits at the front) are validated before dispatch, so
this library never hands `select-layout` anything it has not already verified
tmux itself would have written or would recognize. `main-horizontal-mirrored`
and `main-vertical-mirrored` only exist from tmux 3.5.

## %output is cut by byte count, not by character

Control mode's `control_write_callback` gives each pane with pending output a
byte share of an 8192-byte write buffer and cuts at that byte count, so a
multi-byte UTF-8 character can be split across two `%output` lines for the
same pane. A client must decode each pane's output as one continuous byte
stream — a character cut between two pushes belongs to the push that finishes
it, not the one that started it — rather than decoding each `%output` line on
its own.
