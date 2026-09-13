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

`new-window -c` starts the window's command in the requested directory on
3.2a. A fresh isolated server and an explicit `/bin/sh` command writing `pwd`
to a file verify the directory after the child starts. The integration suite
uses the same shell-observed assertion on every matrix lane. The library does
not gate window directories by tmux version.

| asked for                     | 3.2a        | 3.3a onwards |
| ----------------------------- | ----------- | ------------ |
| `new-window -c <dir>`          | honoured    | honoured     |
| `new-session -d -x 100 -y 40`  | **80x23**   | `100x40`     |

Detached session dimensions remain refused on 3.2a under the existing measured
rule. They were not remeasured by the window-directory probe.

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

## Not covered

- `new-session -E`, which suppresses `update-environment`.
- `new-session -t`, which groups a new session with an existing one.
