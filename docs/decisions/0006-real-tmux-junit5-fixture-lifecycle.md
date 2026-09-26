# 0006. Real-tmux JUnit 5 fixtures: store-owned, fresh-socketed, and swept

Status: Accepted

## Context

**Ownership.** Three fixture-ownership designs ran the same exit-path cases —
success, assertion failure, assumption abort, an exception from the test body,
a timeout, a repeated test, and a test taking several fixtures at once —
sequentially and concurrently, with cleanliness judged from outside by a
creation-time ledger. Extension-instance fields (callback-owned) are simplest
and clean sequentially, but JUnit reuses one extension instance per class, so
those fields are shared mutable state: one test's teardown reached servers
belonging to tests still running, and four cases that passed sequentially
failed *as the tests themselves* under concurrent execution. A caller-closed
resolved value (parameter-owned) is clean only when every test cooperates; the
shared case set, which does not always close, leaked ten live servers. An
idempotent `AutoCloseable` aggregate held in the extension store, released
from a lifecycle callback and closing in reverse acquisition order, passed
both — its one gap was that release depended on the framework's own
`junit.jupiter.extensions.store.close.autocloseable.enabled` setting, closed
by adding an explicit callback release alongside the store's own close so
either path alone suffices and both together are harmless.

**Socket freshness.** Reusing a socket path after `kill-server` loses the new
server on that path a few percent of the time, on every released lane, with no
diagnostic: `new-session -d` exits `0` and prints nothing, and the next
command on the same socket answers `no server running`. A departing tmux
unlinks the socket file it was serving, and a server started on the same path
in that moment can lose its own socket to the predecessor's cleanup. The
failure is of the *path*, not of a shared directory, so consolidating fixtures
onto one directory with per-test socket *names* would reintroduce it.
Separately, killing an already-gone server reports `server exited
unexpectedly` instead of `no server running` on a measurable fraction of
attempts (a fifth of them on tmux 3.7), so a postcondition check — is the
server still there — replaces matching either message.

**Reaping.** A detached tmux server is nobody's child. Killing the owning test
JVM outright (`SIGKILL`) skips shutdown hooks, and the host's temporary-file
cleaner later removes the now-orphaned fixture directory, leaving a server
that is alive, findable by argv, and killable by signal, but unreachable by
socket path — nineteen such servers were found abandoned on one machine. A
tmux-side parent-death watchdog reaps in under a second but was rejected: it
doubles the process count per server, which spends the exact resource
(`fork: No space left on device`) that sharing `/tmp` with sibling libtmux
ports already threatens. A shutdown hook runs on normal exit and `SIGTERM` but
not `SIGKILL`; a startup sweep that reads process ownership from the process
table (`ProcessHandle.allProcesses()`, matched on the `tmux` executable, an
exact `-S` argument, and a socket path under this port's own root) covers what
the hook cannot, without registering or trusting anything the killed run never
got to update. A server is reaped only once its owning pid is confirmed gone,
so a reused pid can at worst skip reaping something already abandoned, never
end something live. Separately, a fixture must never remove its own directory
while its server may still be running: the socket is `kill-server`'s only way
to reach the daemon, and unlinking it first leaves the server orphaned rather
than stopped.

## Decision

Hold fixtures in the extension store as one idempotent `AutoCloseable`
aggregate, released from both a lifecycle callback and the framework's own
store close. Give every fixture a fresh temporary directory and socket path
that has never held a server. Name the socket path with the owning JVM's pid,
run a shutdown hook that ends this JVM's own servers, and sweep servers whose
owner is gone (by process-table ownership, never by a registry) before making
the first server of a run. Check server liveness after any action that might
have ended it; never match tmux's own wording. Never signal a server based on
pid alone — see [0013](0013-server-identity-is-pid-and-start-time.md).

## Consequences

A leaked server is a loud test failure, not a quietly unlinked socket: when a
fixture's tmux will not die, the test fails rather than proceeding. Gradle's
per-module workers and the tmux version matrix's parallel lanes can share one
root safely, because only a server whose owner is provably gone is ever
touched. `scripts/reap-stale-servers.sh` runs the same sweep by hand for a
server that outlived a run in some other way.
