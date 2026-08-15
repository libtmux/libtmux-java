# Waiting on a tmux channel

## Verdict

Return why a wait ended rather than whether it succeeded, and offer a way to
drain a channel before relying on it. tmux's `wait-for` misleads a caller in two
independent ways, and both are silent.

## Measured

One server, one channel, `timeout` bounding each client:

| what happened                              | tmux client exit |
| ------------------------------------------ | ---------------: |
| channel signalled while waiting            |                0 |
| signalled with nobody waiting, then waited |                0 |
| the same channel waited on a second time   |  blocked forever |
| **server killed while waiting**            |            **0** |

## The two traps

**A successful exit does not mean a signal.** Killing the server under a waiter
exits 0, exactly as a real signal does. A caller that treats success as "the
thing I was waiting for happened" proceeds on a server that is gone.

The wake is therefore reported as `SIGNALLED`, `TIMED_OUT` or `SERVER_GONE`,
and the server is checked after the wait rather than believed. When the server
has gone the answer is `SERVER_GONE` whether or not a signal also arrived,
because nothing the wait was guarding can be relied on either way.

**A signal outlives the moment it was sent.** Signalling a channel nobody is
waiting on is remembered, and satisfies the next wait whenever that comes — a
later test, a later run, a different program sharing the server. The third row
above is what shows it is exactly one signal, not a permanently open gate.

So a wait can return `SIGNALLED` having waited for nothing. `drain` consumes a
pending signal if there is one and reports whether there was, which is how a
caller starts from a known state. Two tests pin this: one drains and then times
out, and one deliberately does not drain and shows the wait returning
immediately on a signal sent before it began.

## Why this was not wrapped earlier

A thin wrapper over `wait-for` would have compiled, passed a happy-path test,
and been wrong in both directions under exactly the conditions a synchronisation
primitive exists for. The traps are only visible by killing a server mid-wait
and by signalling a channel nobody is listening to.

## Commands

```console
$ tmux -S "$socket" wait-for -S channel
```

```console
$ timeout 5 tmux -S "$socket" wait-for channel; echo "rc=$?"
```

## Not covered

- `wait-for -L` and `-U`, the lock flavour of the same command.
- Whether a signal survives a server restart; the channel lives in the server,
  so it should not, but that was not measured.
