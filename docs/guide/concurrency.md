# Threads, cancellation, and what runs at once

Every call blocks its thread until tmux answers, and every one is safe to make
from several threads at once. This page says what that promise covers, what
bounds it, and what an interrupted or cancelled call leaves behind.

## Share one server

A `Server` holds nothing a call changes. Share one across threads rather than
opening one per thread. The `Session`, `Window`, and `Pane` it hands out are
immutable views of one capture, so a handle is safe to pass anywhere; a method
that changes tmux changes tmux, not the handle. Close the server once, after the
last call on any thread.

Threads are the ordinary way to do several reads at once, and virtual threads are
the cheap way:

```java
// Given: Server server
server.newSession("left");
server.newSession("right");

int windows = 0;
try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Integer>> counts = server.sessions().stream()
            .map(session -> threads.submit(() -> session.refresh().windows().size()))
            .toList();
    for (Future<Integer> count : counts) {
        windows += count.get();
    }
}
windows >= 2;                                   // → true
```

Several calls that must happen together belong in one
[batch or chain](batching-and-chaining.md), not on several threads: tmux runs a
group as one message, so nothing of this caller's lands between its commands.

## How many run at once

Each call starts one tmux process, and the transport admits a bounded number at
a time: `ServerConfig.Builder.maxConcurrentCommands` for `Server.open`, four
unless set, or whatever `new ProcessTransport(n)` given to `Server.using` says.
`Server.admissionBound()` reports it, so a coroutine dispatcher or effect pool
can be sized to it rather than guessing. A call beyond the bound waits for a
place within its own deadline. One that never gets one fails with
`DispatchOutcome.NOT_DISPATCHED`: tmux never saw it, so sending it again is safe.

The caller may be a virtual thread. The threads that drain tmux's output are not:
a library does not own the virtual-thread scheduler, and unrelated code holding a
carrier inside a `synchronized` block would otherwise stop a drain, fill the
pipe, and hang tmux.

What the library itself holds, counted:

| holder | platform threads | for how long |
| --- | --- | --- |
| `ProcessTransport` with bound `n` | up to `3n`: standard output, standard error and standard input of each running tmux | released after ten idle seconds |
| each attached `ControlClient` | 3: its reader, its error stream, its writer | until it closes |
| each `EventSubscription` | none: `next()` blocks the caller's thread, `poll()` with `onReady` blocks none | — |

## Deadlines and interruption

Every call has a deadline: the configuration's `defaultTimeout`, or the timeout
the call takes. The deadline covers the wait for admission and the command
itself.

Interrupting a thread that is in a call ends the call and keeps the thread's
interrupt status. What tmux did depends on when:

| interrupted | the exception | has tmux run it? |
| --- | --- | --- |
| waiting for admission | `DispatchException`, `NOT_DISPATCHED` | no |
| after the process started | `DispatchException`, `UNKNOWN` | maybe |
| in a wait such as `awaitText` or `Channel.await` | `InterruptedException` | a wait changes nothing |

`UNKNOWN` means read the state back before deciding; never send a mutation again
blindly. [Failures, telemetry, and pane input](operations.md) has the whole retry
rule, and the observer that reports every call's certainty on the thread that
made it.

## One writer per pane

Typing, pasting, and a shell run share one screen. `sendKeys`, `sendLiteral`,
`paste`, `pasteBuffer`, and `run` hold the pane for the call, and another thread
that tries meanwhile is refused with `IllegalStateException` rather than
interleaving its keys. `PaneInput.hold` keeps it for longer. The hold is per JVM:
two processes driving one tmux do not see each other's. See
[operations](operations.md#one-writer-per-pane).

## The control client

`ControlClient.send` may be called from several threads at once: requests queue
in arrival order and each caller gets its own reply. A deadline that passes
before the request is written writes nothing; one that passes after it ends the
client, because the next reply could no longer be matched to its request.

An interrupt is one caller giving up, not tmux failing. Interrupted before its
request is written, the caller gets `DispatchException` with outcome
`NOT_DISPATCHED` and nothing is sent. Interrupted after, it gets outcome
`UNKNOWN`, since tmux may have run the command, while the client carries on:
tmux still answers that request in order, and every other caller and
subscription is unaffected. Cancelling one coroutine or fiber on a shared
client is therefore safe.

A subscription has one reader. Reads from different threads one after another
are fine, as a coroutine or fiber moves between threads, but a read that
overlaps another is refused with `IllegalStateException`: two readers would
split one sequence and its gaps between them. `stream()` and `publisher()` take
the subscription whole, so any other read after them is refused too. For a
second reader, subscribe again; each subscription gets every event.

`next()` blocks a thread while nothing arrives. `poll()` never blocks, and
`onReady(Runnable)` arms a one-shot wakeup for when something is readable or
the subscription ends, so a coroutine or event loop reads without holding a
thread. The callback runs on the client's reader thread and must only wake the
reader. Each subscription
buffers a fixed number of events; one that falls behind loses the oldest and
reads a `Delivery.Gap` saying how many. [Streaming](streaming.md) has the rest.

## From Kotlin and Scala

Kotlin's suspending waits and reads block a thread of the `dispatcher` they are
given, `Dispatchers.IO` unless you pass one, and cancelling one interrupts that
thread. Plain calls belong on `Dispatchers.IO` too. [Kotlin](kotlin.md#blocking-calls-from-a-coroutine)
has the rule.

The Scala Cats facade admits a bounded number of calls per server and runs each
on a blocking worker. Cancelling one that tmux may already have run ends in
`Outcome.Canceled`, and the observer reports the command as `UNKNOWN`.
[Scala](scala.md) links the facade's own execution notes.
