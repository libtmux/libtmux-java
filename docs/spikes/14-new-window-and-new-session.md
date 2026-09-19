# new-window and new-session across the supported range

## Verdict

Neither command has gained or lost a flag since 3.2a, so neither needs a feature
gate. Two behaviours still need handling, and one flag is deliberately not
exposed.

## The flags never moved

`new-window` and `new-session` declare byte-identical option strings in 3.2a,
3.6 and 3.7b:

```c
/* cmd-new-window.c  */ .args = { "abc:de:F:kn:PSt:" }
/* cmd-new-session.c */ .args = { "Ac:dDe:EF:f:n:Ps:t:x:Xy:" }
```

Unlike `split-window`, which gained six flags in 3.7, there is nothing here for
a version rule to protect. What differs between releases is behaviour, not
vocabulary.

## Window directories and detached session dimensions

`new-window -c` starts the window's command in an absolute directory on every
supported release, 3.2a included. A fresh isolated server and an explicit
`/bin/sh` command writing `pwd` to a file verify the directory after the child
starts.

What 3.2a lacks is the resolution step. `spawn_pane` hands the argument to the
child unchanged, so a relative path is resolved against whatever directory the
server was started in, and the child falls back to `$HOME` when that misses.
3.3a added the branch that prefixes a non-absolute `-c` with the requesting
client's working directory, which is the calling process:

```c
/* spawn.c, 3.3a */
if (*cwd != '/') {
        xasprintf(&new_cwd, "%s/%s", server_client_get_cwd(c, target->s), cwd);
```

| asked for                     | 3.2a        | 3.3a onwards |
| ----------------------------- | ----------- | ------------ |
| `new-window -c /abs/dir`      | honoured    | honoured     |
| `new-window -c sub`           | **$HOME**   | honoured     |
| `new-session -d -x 100 -y 40` | **80x23**   | `100x40`     |

A relative directory is refused below 3.3a rather than sent, on the same
reasoning as the 3.7 split options: a window that silently started in the home
directory is indistinguishable from the window that was asked for. An absolute
one is not gated at all.

Detached session dimensions remain refused on 3.2a under the existing measured
rule. They were not remeasured by the window-directory probe.

`split-window`, `new-session` and `respawn-pane` reach the same `spawn_pane`
code and drop a relative directory the same way on 3.2a. Only `new-window` is
gated so far.

## `-S` reports nothing

`new-window -S` selects a window that already has the wanted name instead of
making a second one. It does that correctly on every lane — one window survives,
not two — but the `-P -F` template expands to nothing:

| lane   | `-P -F '#{window_id}'` output | windows named `reused` |
| ------ | ----------------------------- | ---------------------- |
| `3.2a` | *(empty)*                     | 1                      |
| `3.3a` | *(empty)*                     | 1                      |
| `3.6`  | *(empty)*                     | 1                      |
| `3.7b` | *(empty)*                     | 1                      |

So the usual trick — read the new object's id out of the creating command — does
not work here, and a caller asking to select an existing window has to be
answered from a lookup instead. Every other creating command in this library
reports what it made; this is the one that does not.

## `-A` is not exposed

`new-session -A` attaches when the session already exists. That is an attach, and
an attach needs a terminal:

```console
$ tmux -S "$sock" new-session -A -D -s existing
open terminal failed: not a terminal
```

Every lane, with `-d`, and with `-D` as the manual suggests. A library is most
often called from something that has no terminal — a build, a service, an agent
— so a flag that works only under a tty is a trap rather than a feature. Asking
whether the session exists and creating it if not does the same job from any
process, and says what it is doing.

## Commands

```console
$ tmux -S "$sock" -f /dev/null new-window -d -S -n reused -P -F '#{window_id}'
```

```console
$ tmux -S "$sock" -f /dev/null new-session -d -s sized -x 100 -y 40 -P -F '#{window_width}x#{window_height}'
```

Run from a directory that holds neither `sub` nor the server, so the 3.2a
fallback is visible:

```console
$ tmux -S "$sock" new-window -d -n rel -c sub -P -F '#{pane_current_path}'
```

## Not covered

- `new-session -E`, which suppresses `update-environment`.
- `new-session -t`, which groups a new session with an existing one.
