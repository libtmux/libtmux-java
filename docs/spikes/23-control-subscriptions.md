# Being told what changed

## Verdict

Watch a tmux server with `refresh-client -B`, not by polling and not by tapping
the pty. tmux re-expands a registered format on its own one-second timer and
writes a notification only when the value differs, so the comparison happens in
the server and a client does nothing at all between changes.

Quote every argument. tmux's own lexer reads a control-mode request, and `#` in
an unquoted argument starts a comment.

## What a subscription is

```
refresh-client -B <name>:<what>:<format>
```

`what` selects the targets, and the parsing is exact
([`cmd-refresh-client.c:47`](https://github.com/tmux/tmux/blob/3.7b/cmd-refresh-client.c#L47-L78)):

| `what` | subscribed to |
| ------ | ------------- |
| `%*` | every pane |
| `%N` | one pane |
| `@*` | every window |
| `@N` | one window |
| anything else, including empty | the attached session |

A name with no colon after it removes the subscription instead.

Each change arrives as one line naming its own target:

```
%subscription-changed <name> $<session> @<window> <index> %<pane> : <value>
```

## Measured

tmux 3.7b, one server, one control client, three subscriptions registered:

| what happened            | pushed                                            |
| ------------------------ | ------------------------------------------------- |
| client attached          | `wincount 1`, `panecmd zsh`, `winnames zsh`       |
| `new-window`             | `wincount 2`, `panecmd` for the new pane, `winnames freshly-made` |
| `rename-window`          | `winnames renamed-now`                            |
| `kill-window`            | `wincount 1`                                      |
| nothing, for four seconds | nothing                                          |

Eight notifications, one per actual change, and the initial value once. The idle
interval produced none: tmux compares against the last value it sent
([`control.c:857`](https://github.com/tmux/tmux/blob/3.7b/control.c#L857-L876))
and the timer is one second
([`control.c:1051`](https://github.com/tmux/tmux/blob/3.7b/control.c#L1040-L1052)).

`%window-add`, `%window-renamed` and `%session-changed` arrive alongside, with no
subscription needed; those are the unconditional notifications in
[`control-notify.c`](https://github.com/tmux/tmux/blob/3.7b/control-notify.c).

## The trap that cost the first run

Every subscription was rejected:

```
%begin 1786900371 351 1
parse error: syntax error
%error 1786900371 351 1
```

The format was sent unquoted, and tmux reads `#` as the start of a comment before
it ever reaches the format expander. Single-quoting each argument fixes it, which
is what `ControlClient.line` already does for every request it sends — so the
Java path never had the defect, and this is recorded because the shell probe did.

## Why not the alternatives

**Polling `capture-pane`.** Works, and is what a wait tool has to do for text the
caller did not author. As a change detector it is strictly worse: a process per
pane per interval, and it cannot tell "no change" from "not looked yet".

**Tapping the pty with `pipe-pane`.** Rejected. tmux keeps a single `pipe_fd` per
pane and starting a new pipe frees the old one, so an internal pipe silently
destroys whatever logging a person started with `pipe-pane` — and vice versa. It
also carries raw pty bytes rather than the rendered grid, so it fires on the echo
of a command just sent.

**Latency was not the deciding factor either way.** The sibling Python port
measured a control-mode wake at ~18 ms against ~21 ms for 50 ms polling. What
subscriptions buy is not speed; it is that nothing runs while nothing happens.

## The cost, which is not zero

A subscription requires an attached client, and an attached client is a real
change to the server: `#{session_attached}` becomes true, and anything asking
"is a person looking at this" would say yes.

So watching is off unless asked for, and the client is identified by asking it for
its own `#{client_name}` — through itself, so the answer is not a guess — and
excluded from what `tmux_list_clients` reports.

## Commands

```console
$ tmux -S /tmp/libtmux-java-dev/subspike -C attach-session -t probe
```

```console
$ printf "refresh-client -B 'winnames:@*:#{window_name}'\n"
```
