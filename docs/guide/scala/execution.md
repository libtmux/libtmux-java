# Execution

The direct-style facade runs operations immediately. The Cats facade
constructs lazy effects: creating a read or mutation effect performs no tmux
I/O, and evaluating it again repeats the operation. Both delegate commands and
transport behavior to Java. Choose the [direct-style server][server] for
ordinary synchronous code or the [Cats server][cats-server] inside a managed
effect scope.

Here `config` is a Java `ServerConfig` for an existing tmux server. The same
read effect observes the value present at each evaluation:

<!-- snippet: scala-io: execution-repeats-a-read -->
```scala
import _root_.cats.effect.IO
import io.github.libtmux.scaladsl.cats.{config => _, *}
import io.github.libtmux.scaladsl.cats.{Server => ScalaServer}

ScalaServer.resource[IO](config).use { server =>
  val name = "scala-guide-session"
  val read = server.hasSession(name)
  for {
    before <- read
    _ <- server.newSession(name)
    after <- read
    _ <- IO {
      assert(!before)
      assert(after)
    }
  } yield ()
}
```

Captured `info`, session windows, window panes, and client attachments are pure
reads: generated as plain values in both layers, never wrapped in `F` on the
Cats side, since the catalog marks them `CAPTURED`. Filtering their immutable
collections does not refresh them. Listing, refresh, format expansion, pane
mode inspection, and mutation perform I/O. `Pane.awaitText` polls captured
text.

## Admission and blocking work

Cats operations run through an [interruptible blocking boundary][execution],
including a server or control client's own acquisition step. An owned server
accepts `maxConcurrentCalls` from one through four. A borrowed server accepts
a positive bound, but that bound covers only calls through that facade. Its
owner must account for other users and the underlying transport's capacity.

`Pane.awaitText` and `Pane.await` reserve one facade call for work that can
release the wait (`Execution.waiting`), and require capacity of at least two.
`Pane.run` uses ordinary admission: its private completion channel is normally
signalled by the pane's shell.

This bounds admitted calls; it does not make Java I/O thread-free. Waiting
operations occupy blocking workers, and process transport uses its own workers
to service child processes. The limit does not bound the number of fibers a
caller may queue. Use bounded effect traversal when submitting a large
collection. Ordered traversal preserves result order, while concurrent tmux
mutations can still reach the daemon in a different order.

Prompt cancellation depends on a borrowed transport honoring interruption and
completing its cleanup.

Command timeouts start when Java receives the operation, after Scala admission.
An effect timeout can bound admission and execution together, but cancellation
still waits for the operation's cleanup. A timeout is not a promise that a
dispatched mutation had no effect.

## Failures and cancellation

Ordinary failures preserve Java's exception and dispatch certainty: the sealed
`LibTmuxException` tree matches directly from Scala (see
[errors](../scala.md)), and `DispatchException#safeToRetry`
answers the question a caller usually has without a catalog lookup. A
`NOT_DISPATCHED` transport failure differs from `UNKNOWN`: the latter may have
changed tmux. Raw `Server.cmd` returns Java's own `transport.CommandResult`, so
a nonzero exit remains data with its stdout and stderr.

Cats cancellation interrupts local work, waits for owned cleanup, and ends in
`Outcome.Canceled` whether or not the command reached tmux. Cancellation carries
no error, so the facade does not turn it into one. To learn whether a canceled
command was dispatched, set an `OperationObserver` on the Java `ServerConfig`:
the process transport reports `UNKNOWN` for a command it interrupted after
starting. Treat a canceled mutation as possibly dispatched; the facade does not
retry it, roll it back, or switch transports. A daemon-side shell job can
continue after its requesting client is canceled. Closing a borrowed transport
from its owner can instead produce an ordinary Java `UNKNOWN` failure.

## Control replies

`Control.acknowledge` reports a [protocol reply][control]. `accepted` means a
successful reply frame arrived; deferred tmux work can still be running. Use a
separate completion signal for that work. Canceling an active control request
can close its attachment and affect queued requests and observations, so use
separate attachments when their lifetimes must be independent.

`Control.isAlive` reports whether that process is still running.
`Control.standardError` is the text that process wrote to its error stream,
at most 4096 bytes. `standardErrorTruncated` says the stream continued past
that bound. `Control.watch` asks tmux to push a format when its value
changes, and `unwatch` removes that name. A target that is not a pane or
window id watches the attached session.

[server]:
  ../../../libtmux-scala/src/main/scala/io/github/libtmux/scaladsl/Server.scala
[cats-server]:
  ../../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
[execution]:
  ../../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Execution.scala
[control]:
  ../../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Control.scala
