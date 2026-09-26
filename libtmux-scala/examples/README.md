# Scala examples

These programs require an existing isolated tmux server at the endpoint
supplied by the caller. Each creates and removes its own sessions and closes
its clients. The existing daemon remains running. Isolation makes the
notification example's deliberate overflow reproducible, while the subscription
counts every control notification it receives.

| Program | Demonstrates |
| --- | --- |
| [BlockingWorkspace][blocking] | Blocking workspace operations |
| [CaptureConcurrently][capture] | Bounded concurrent capture |
| [ObserveChanges][observe] | Notification loss and reconciliation |
| [ResourceBoundaries][resources] | Resource ownership and cancellation |

`BlockingWorkspace` uses the direct-style opaque handles, a layout change and
the typed field query DSL (`Pane.active`, `Pane.id`, `.matching`,
`exactlyOne`). It filters captured panes and checks a missing session lookup.

`CaptureConcurrently` splits a window into three panes and captures them
concurrently, retaining each capture's own pane identity in the result.

`ObserveChanges` watches a session's live state as a Cats `Signal`
(`LiveServer`, over Java's own `ServerMirror`) and reconciles it against a
window rename, checking the background poller's observed outcome.

`ResourceBoundaries` borrows a Java client without owning it, cancels a
dispatched wait, and checks that cancellation reaches only that call: the
session, and the Java owner's daemon, both outlive it.

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
