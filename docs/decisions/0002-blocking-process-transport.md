# 0002. Admission-bounded platform pumps drain a tmux process

Status: Accepted

## Context

Three drain strategies for a blocking tmux child process were built against
one shared contract: exact channel preservation, a launch gate that admits no
process after `close()`, retrying destruction escalation, and survival under a
carrier-starved virtual-thread scheduler.

Virtual-thread pipe drains pass every gate but one. On the pinned Temurin 21
toolchain, `ProcessPipeInputStream` (the JDK's process pipe stream on Unix)
subclasses a monitor-locked buffered stream, so every pipe read runs inside
`synchronized (this)` and pins whatever carrier performs it. JDK 21 has no
unpinned monitor blocking — that arrives only with
[JEP 491](https://openjdk.org/jeps/491) — so a drain thread that is itself a
virtual thread can starve every other virtual thread sharing its carrier. With
one carrier held by an unrelated thread blocked inside a monitor, the drains
never mount, the child's pipe fills, and the request dies as a deadline
exceeded rather than as a decoded result. Redirecting both channels to
temporary files survives the same gate, but writes captured pane content to
disk, adds disk exhaustion as a failure mode, needs correctness across seven
termination paths instead of one pool lifecycle, and cannot carry a future
streaming control-mode transport.

`jdk.VirtualThreadPinned` cannot see this: the event fires only when a virtual
thread parks while pinned, and a monitor-locked pipe read never parks. A
contender that pins a carrier on every read it performs still records zero
pin events. The gate that actually separates the contenders is carrier
starvation — flood both pipes while an unrelated thread holds the only
carrier — not the pinning recording.

## Decision

Drain a child's stdout and stderr on a fixed pool of prestarted platform
threads, two workers reserved per admission permit. A caller acquires one
permit before the child launches; starting the child, registering it, and
publishing it for destruction happen inside a section `close()` cannot
interleave with. The caller itself may block in `Process.waitFor()` from a
virtual thread, since that call does not pin. Destruction retries across
interruption instead of returning on the first one, and a permit is withheld
until both of its pump workers have actually left the pipe, not as soon as the
request finished.

## Consequences

The transport owns and sizes a worker pool rather than spawning one thread per
in-flight command. A caller may safely invoke the blocking API from a virtual
thread; `CarrierStarvationTest` runs the suite under a one-carrier scheduler to
keep that true. A child `close()` destroys is reported as an unknown outcome,
not as an ordinary exit, because a caller parked in `waitFor` cannot otherwise
tell a force-killed child from tmux exiting on its own signal.
