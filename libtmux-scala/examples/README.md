# Scala examples

These programs require an existing isolated tmux server at the endpoint
supplied by the caller. Each creates and removes its own sessions and closes
its clients. The existing daemon remains running. Isolation makes the
notification example's exact loss count reproducible.

| Program | Demonstrates |
| --- | --- |
| [BlockingWorkspace][blocking] | Blocking workspace operations |
| [CaptureConcurrently][capture] | Bounded concurrent capture |
| [ObserveChanges][observe] | Notification loss and reconciliation |
| [ResourceBoundaries][resources] | Resource ownership and cancellation |

`BlockingWorkspace` uses layouts, literal text, key names, line input and a
completion signal. It filters captured handles, checks missing values and
distinguishes linked contexts from physical pane identity.

`CaptureConcurrently` runs at most two captures concurrently. It retains input
order, pane identity, window context and original output rows.

`ObserveChanges` consumes typed notifications with bounded drop-oldest
buffering. It checks cumulative loss and reconciles current state with a
snapshot.

`ResourceBoundaries` borrows a Java client, checks failed attachment cleanup
and cancels dispatched work. It verifies that earlier effects survive
cancellation and that the Java owner remains usable after the borrow ends.

Each `main` takes three arguments: the tmux executable, socket path and tmux
configuration file. It builds a `ServerConfig` and calls the same `run` method
the program exposes to Scala callers. The Cats examples execute their `IO` in
`main`; constructing that value alone does not run it.

The `examples/test` sbt task discovers every main and invokes it against a
fresh owned fixture. The fixture records an actual session creation, checks
the original sessions and clients remain after each program, and verifies
owned process and worker cleanup. The index, discovered mains and compiled
source hashes must agree. `TMUX_TEST_BINARY` selects the executable used by
the fixture.

The capture example retains decoded process output. The notification example
does not provide producer backpressure or locate an exact gap within delivered
events. The cancellation example does not retry an uncertain request or imply
that canceling a client reverses work already dispatched to tmux.

[blocking]:
  src/main/scala/io/github/libtmux/scaladsl/examples/BlockingWorkspace.scala
[capture]:
  src/main/scala/io/github/libtmux/scaladsl/examples/CaptureConcurrently.scala
[observe]:
  src/main/scala/io/github/libtmux/scaladsl/examples/ObserveChanges.scala
[resources]:
  src/main/scala/io/github/libtmux/scaladsl/examples/ResourceBoundaries.scala
