# Teardown that outlives the process meant to do it

## Verdict

A shutdown hook plus a sweep that reads ownership from the process table. The
tmux-side watchdog reaps faster and was rejected anyway: it costs two processes
per server, and too many processes is the failure this project already has.

Ownership is encoded in the socket path — `libtmux-<owner pid>-<random>/s` — so
a sweep can tell a server nobody owns from one another run is still using.

## The problem, measured

Nineteen tmux servers were found alive on this machine, started between 05:42
and 07:20, all matching this fixture's argv. Every one had lost its socket:

```console
$ tmux -S /tmp/libtmux-java-test/libtmux-9476196602789674708/s kill-server
error connecting to … (No such file or directory)
```

Reproduced exactly. A server whose directory is removed underneath it stays
running, stops being reachable by socket path, remains identifiable by argv, and
still answers to a signal:

| after the directory is removed | result |
| ------------------------------- | ------ |
| socket file exists              | no     |
| daemon alive                    | yes    |
| reachable via `tmux -S`         | no     |
| findable in the process table   | yes    |
| ends on `SIGTERM`               | yes    |

The sequence is: the test JVM is killed, so no finalizer runs; the server
survives; the host's temporary-file cleaner later removes the directory; the
server is now unreachable by every means except its argv.

## Python libtmux has the same hole

Worth stating, because it rules out a translation. `pytest_plugin.py` uses
`request.addfinalizer`, which is exactly this extension's `afterEach`: it runs
only if the process lives long enough to run it.

Its `_reap_test_server` does contribute one idea this port had already adopted
independently — kill, then unlink, because "tmux does not reliably `unlink(2)`
its socket on non-graceful exit". And one it deliberately does not: Python
suppresses cleanup failures so they cannot mask a test failure, where this
extension raises when a server would not die, because a leaked server is not a
detail a suite should keep quiet about.

So this is a redesign, not a translation.

## Bakeoff

| approach                          | survives `SIGKILL` | cost                     | reaps when        |
| --------------------------------- | ------------------ | ------------------------ | ----------------- |
| A. JVM shutdown hook              | no                 | nothing                  | at JVM exit       |
| B. sweep by process ownership     | yes                | one process-table scan   | next run starts   |
| C. `destroy-unattached`           | —                  | —                        | never viable      |
| D. tmux-side parent-death watchdog| yes                | 2 processes per server   | ~0.2s             |

**C** was rejected without measuring: every fixture session is detached by
design, so tmux would destroy them the moment they were made.

**D** works, and works quickly. A `run-shell -b` loop polling the owner's pid
reaped the server 0.2 s after `kill -9` of its owner. It was still rejected.
`.github/CONTRIBUTING.md` records this machine failing with `fork: No space
left on device` when too many tmux servers are alive; doubling the processes
each server holds spends exactly the resource that is already short,
permanently, to shorten a window that a sweep closes for free. It also needs
the lane's own tmux binary inside a shell string, which the matrix would get
wrong.

**A** was measured rather than assumed:

| signal to the JVM | shutdown hook runs |
| ----------------- | ------------------ |
| `SIGTERM`         | yes                |
| `SIGKILL`         | no                 |

(`SIGINT` was also tried and did not run the hook, but the process under test
was a background job in a non-interactive shell, which has `SIGINT` ignored
before the JVM ever sees it. That measurement says nothing about the JVM and is
not claimed.)

So A covers everything except being killed outright, and B covers that.

## What the sweep may and may not do

`ProcessHandle.allProcesses()` reads argv for processes this JVM did not start,
so no `/proc` parsing and no `pgrep`:

```
command=/usr/local/bin/tmux
arguments=[-S, /tmp/libtmux-java-dev/ph/s, -f, …, new-session, -d, -s, spike]
```

The spike immediately found the trap. Filtering on
`commandLine().contains(path)` matched **the spike's own shell**, whose command
line happened to mention the socket. Three conditions are therefore required
together: the executable is `tmux`, `-S` appears as an exact argument, and the
socket lies under this port's root.

The fourth condition is what makes it safe to run unconditionally. Gradle forks
a test worker per module and the matrix runs eight lanes, all sharing one root,
so reaping every server under it would kill runs still using them. The owner's
pid is read from the socket's directory name and a server is reaped only when
that pid is gone.

A reused pid can only cause a server that *was* abandoned to be skipped. It can
never cause a live one to be ended, which is the direction to be wrong in.

## Proof

A server owned by a pid known to have exited, planted under the real socket
root, then one ordinary test run:

```
planted orphan owned by dead pid 399369 -> alive: yes
…
planted orphan after the run -> alive: no
```

`AbandonedServerTest` holds all three rules: an abandoned server is reaped, a
server whose owner still runs is not, and a socket naming no owner is not this
sweep's to judge.

## Not covered

- Whether a Gradle test worker receives `SIGTERM` when a build is cancelled. If
  it does, A handles cancellation; if it is killed outright, B does. Both paths
  are covered, so the question was not worth the measurement.
- Reaping across users. The sweep only sees processes this user may signal,
  which is the correct limit rather than a gap.
