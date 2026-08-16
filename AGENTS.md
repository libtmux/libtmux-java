# AGENTS.md

Guidance for agents working on libtmux for Java. This repository is the whole
of the port; nothing outside it governs the Java code.

## Keep off other ports' sockets

Sibling libtmux ports — Python, Go, Rust, TypeScript, C#, C++ — are worked on in
parallel on this machine, and every one of them starts real tmux servers in
`/tmp`. Their debris outlives their test runs: servers from an exited benchmark
stay up, holding ptys, until something kills them.

**Every tmux server this project starts belongs under a path that names this
port.** Use one of:

- `/tmp/libtmux-java-test/…` — anything a test starts.
- `/tmp/libtmux-java-dev/…` — anything started by hand while investigating.

Never `/tmp/libtmux-…` on its own, and never the default socket. `libtmux-` is
the prefix a sibling port is also using, which is the whole problem.

Why it matters more than tidiness: a suite sharing `/tmp` with another port's
leftovers fails intermittently, under load, in places that have nothing to do
with the change under test — `SERVER_GONE`, misframed rows, `fork: No space
left on device`. Every one of those reads as a regression in whatever you last
touched, and hours go into a bug that was never yours.

Before blaming a change for an intermittent real-tmux failure, count what is
already running:

```console
$ pgrep -c tmux
```

Then find whose it is, since a socket path exists only in the process's own
command line:

```console
$ for p in $(pgrep tmux); do tr '\0' ' ' < /proc/$p/cmdline | rg -o '\-S [^ ]+'; done
```

Kill only sockets under this port's roots. Another port's servers are not
yours to reap.

## Socket paths are short by necessity

A unix socket path cannot exceed about 104 bytes, and tmux reports a longer one
as `error connecting to … (File name too long)`. That rules out sockets under a
build directory or a deep scratch path, and it is why the roots above are short.

## Commands

Everything, including formatting and the static analysis gates:

```console
$ ./gradlew check
```

Against every supported tmux release:

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=/path/to/tmux/builds
```

Regenerate the measured comparison of the carriers, which is never hand-edited:

```console
$ ./gradlew modeBenchmark -PlibtmuxTmux=/path/to/tmux
```

## Carriers change cost, not answers

`ExecutionMode` chooses how a command travels, and a suite can be run under any
of them:

```console
$ LIBTMUX_MODE=control ./gradlew check
```

A failure that appears only under one carrier is a real finding — the library
claims the answer does not depend on the carrier, and
`ExecutionModeConformanceTest` is where that claim is gated. Confirm it against
a clean `/tmp` first: the failure modes of cross-port debris and of a genuine
carrier defect look alike.

## Comments earn their maintenance cost

Keep an implementation comment only when losing it would force a future
maintainer to rediscover a consequential, non-obvious fact that the code,
types, assertions, and tests do not already communicate. It states a durable
truth about the shipped system rather than the author's reasoning, and it does
not restate a value or a fact that can change without it — a comment that
duplicates either goes stale silently. Write it as tersely as a mature,
long-lived library would.

Delete comments that narrate, restate, speculate, excuse, or preserve
development history, and prefer deletion in the borderline case. What survives
is what a reader could not recover from the code.

Javadoc on the public surface — summaries, `@param` descriptions, and
examples — is judged on the other axis: what it is worth to a caller, not
whether it is non-obvious. It stays precise, succinct, and maintainable.
