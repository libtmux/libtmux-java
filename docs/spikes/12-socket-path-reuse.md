# Reusing a socket path after kill-server

## Verdict

Never start a server on a socket path that has held one before. Every fixture
takes a fresh directory, and that is load-bearing rather than tidy.

## Measurement

Two arms, forty iterations each, on every supported lane. Both arms end by
asking `has-session` whether the server they just started is reachable.

- **reused** — `kill-server`, `new-session`, `kill-server`, `new-session`, on one
  path.
- **fresh** — `new-session` on a path nothing has used.

| lane   | reused | fresh |
| ------ | ------ | ----- |
| `3.2a` | 3/40   | 0/40  |
| `3.3a` | 1/40   | 0/40  |
| `3.4`  | 0/40   | 0/40  |
| `3.5`  | 3/40   | 0/40  |
| `3.6`  | 1/40   | 0/40  |
| `3.7`  | 1/40   | 0/40  |
| `3.7a` | 2/40   | 0/40  |
| `3.7b` | 1/40   | 0/40  |

Counts are servers that were gone by the next command. The fresh arm never lost
one on any lane; 3.4's clean column is a sample size, not an exemption.

## The failure is silent

`new-session -d` exits 0 and prints nothing. The next command on the same socket
answers `no server running`. Exit status carries no signal at all, so
provisioning that trusts it inherits a few-percent flake with no diagnostic —
and one that lands on whatever command happens to run next, which is why the
first sweep to hit this reported it against three unrelated flags.

## Why it is not a per-command bug

The first sighting looked like `split-window -E` killing a 3.3a server. Removing
the flag kept the failure; removing the preceding `kill-server` removed it. The
flag was a bystander. A departing tmux unlinks the socket file it was serving,
and a server started on that path in the same moment can lose its own socket to
the predecessor's cleanup.

## What this means here

`TmuxExtension` already takes a fresh `Files.createTempDirectory` per fixture and
puts the socket inside it, so the race cannot reach it, and
`verifyPromisedSocket` would catch it at startup if it did. That is the invariant
to keep: consolidating fixtures onto a shared directory with per-test socket
*names* would reintroduce this, because the reuse that matters is of the path,
not of the directory.

## A departing server also changes what it says

The same exit window changes tmux's wording, which matters to anything matching
on it. Killing a server that is already gone, thirty attempts per lane:

| lane   | `no server running` | `server exited unexpectedly` |
| ------ | ------------------- | ---------------------------- |
| `3.2a` | 30/30               | 0/30                         |
| `3.3a` | 29/30               | 1/30                         |
| `3.4`  | 29/30               | 1/30                         |
| `3.5`  | 28/30               | 2/30                         |
| `3.6`  | 29/30               | 1/30                         |
| `3.7`  | 24/30               | **6/30**                     |
| `3.7a` | 29/30               | 1/30                         |
| `3.7b` | 29/30               | 1/30                         |

Both mean the same thing to a caller asking for the server to be gone. Matching
the first message alone makes an idempotent kill raise on a few percent of calls
and on a fifth of them on 3.7 — which is how this was found, as one lane of the
matrix failing a test that had passed everywhere else.

`Server.killServer` checks whether the server is still there instead of reading
the message. The wording is not a contract; the postcondition is.

## Commands

```console
$ tmux -S "$sock" kill-server; tmux -S "$sock" -f /dev/null new-session -d -s base
```

```console
$ tmux -S "$sock" has-session -t base
```

## Not covered

- Whether a delay between kill and create closes the window, and how long.
- Whether the loser is the departing server's `unlink` or the arriving server's
  `bind`; only the outcome was measured.
- Abstract sockets, and socket paths on anything but a local filesystem.
