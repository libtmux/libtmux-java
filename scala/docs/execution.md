# Execution

The blocking facade runs operations immediately. The Cats facade constructs
lazy effects: creating a capture, refresh, or mutation effect performs no tmux
I/O, and evaluating it again repeats the operation. Both delegate commands and
transport behavior to Java. Choose the [blocking server][blocking-server] for
ordinary synchronous code or the [Cats server][cats-server] inside a managed
effect scope.

Here `config` is a Java `ServerConfig` for an existing tmux server. The same
read effect observes the value present at each evaluation:

<!-- snippet: scala-io: execution-repeats-a-read -->
```scala
import _root_.cats.effect.IO
import io.github.libtmux.scaladsl.cats.{Server => ScalaServer}

ScalaServer.resource[IO](config).use { server =>
  val read = server.globalOptions.get("@scala-guide")
  for {
    _ <- server.globalOptions.set("@scala-guide", "first")
    first <- read
    _ <- server.globalOptions.set("@scala-guide", "second")
    second <- read
    _ <- IO {
      assert(first.contains("first"))
      assert(second.contains("second"))
    }
  } yield ()
}
```

Captured `info`, session windows, window panes, and client attachments are pure
reads. Filtering their immutable collections does not refresh them. Listing,
refresh, format expansion, pane mode inspection, and mutation perform I/O.
`Pane.awaitText` polls captured text; use a channel signal when the producer can
report its own completion.

## Admission and blocking work

Cats operations run through an [interruptible blocking boundary][execution].
An owned server accepts `maxConcurrentCalls` from one through four. A borrowed
server accepts a positive bound, but that bound covers only calls through that
facade. Its owner must account for other users and the underlying transport's
capacity.

`Channel.await` and `Pane.awaitText` reserve one facade call for work that can
release the wait. Both require capacity of at least two. Channel waits also
use Java's transport reservation. Ordinary raw commands cannot infer which
other requests they depend on; avoid filling every available call with raw
waits whose signals need that same scope. `Pane.run` uses ordinary admission:
its private completion channel is normally signalled by the pane's shell.

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

Ordinary failures preserve Java's exception and dispatch certainty. A
`NOT_DISPATCHED` transport failure differs from `UNKNOWN`: the latter may have
changed tmux. Raw `Server.cmd` returns a [command result][command-result], so a
nonzero exit remains data with its stdout and stderr. Typed operations retain
the Java API's failure behavior.

Cats cancellation interrupts local work and waits for owned cleanup. An
`Outcome.Canceled` does not carry the Java exception that interruption may have
produced. Treat canceled mutations as possibly dispatched; the facade does not
retry them, roll them back, or switch transports. A daemon-side shell job can
continue after its requesting client is canceled. Closing a borrowed transport
from its owner can instead produce an ordinary Java `UNKNOWN` failure.

## Groups and control replies

Cats [batches][cats-batch] and [chains][cats-chain] are immutable plans. Each
evaluation of `run` builds a fresh Java group. Construction performs no tmux
I/O; chain layout validation that needs a version query happens during `run`.
Blocking builders retain their immediate Java behavior.

Read [batch results][batch-result] as Java's reported attribution. A runtime
failure can identify completed, failed, and skipped operations, but a parser
rejection can label the first operation failed even when another operation
caused the rejection and none ran. `reportedFailure` does not independently
prove the failing command's location. Preserve the raw outputs when comparing
execution modes; do not silently normalize their different line shapes.

`Control.acknowledge` reports a [protocol reply][control]. `accepted` means a
successful reply frame arrived; deferred tmux work can still be running. Use a
separate completion signal for that work. Canceling an active control request
can close its attachment and affect queued requests and observations, so use
separate attachments when their lifetimes must be independent.

[blocking-server]:
  ../core/src/main/scala/io/github/libtmux/scaladsl/blocking/Server.scala
[cats-server]:
  ../cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
[execution]:
  ../cats/src/main/scala/io/github/libtmux/scaladsl/cats/Execution.scala
[command-result]:
  ../core/src/main/scala/io/github/libtmux/scaladsl/CommandResult.scala
[cats-batch]:
  ../cats/src/main/scala/io/github/libtmux/scaladsl/cats/Batch.scala
[cats-chain]:
  ../cats/src/main/scala/io/github/libtmux/scaladsl/cats/CommandChain.scala
[batch-result]:
  ../core/src/main/scala/io/github/libtmux/scaladsl/BatchResult.scala
[control]:
  ../cats/src/main/scala/io/github/libtmux/scaladsl/cats/Control.scala
