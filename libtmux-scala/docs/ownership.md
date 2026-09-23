# Ownership

Opening a Scala server owns a Java client. Closing that client releases its
transport and local workers; it does not kill the tmux daemon. `killServer` is
an explicit, separate operation. A borrowed facade leaves the original Java
client under its existing owner's control.

## Blocking scopes

[`blocking.Server.open`][blocking-server] returns an `AutoCloseable` client for
`scala.util.Using.resource` or an explicit `try`/`finally` scope. Its `close` is
idempotent. `blocking.Server.fromJava` borrows a client: closing that Scala
facade disables its operations without closing the Java client.

Handle operations use the owning Scala server's closed-scope check. Captured
information and traversal remain readable after closure, but refresh and
mutation through those handles fail. The blocking facade does not supervise
concurrent borrowed calls; its owner controls the Java transport's lifetime.
Coordinate that lifetime with every other user of the borrowed client.

## Effect scopes

[`cats.Server.resource`][cats-server] acquires the client when its `Resource`
runs. `cats.Server.fromJava` also returns a `Resource`, with borrowing
semantics. Both scope the operations submitted through the returned facade.
Release rejects new work, cancels queued and running operations, waits for their
finalizers, and then closes the owned client if there is one.

Returning a handle or an unevaluated effect from the resource body does not
extend its lifetime. The handle's captured data remains available; executing
its effect after release fails. Release cancels operations still running; an
operation can finish before that cancellation reaches it. A caller that masks
cancellation can receive a scope-closed failure instead of waiting indefinitely
for a canceled child operation.

Partial acquisition failure and failure in the resource body still release
what the resource acquired. Cancellation also waits for cleanup. Borrowing
changes which client is closed, not the requirement to release the facade's
own work. If an external owner closes a borrowed transport while a request is
dispatched, that request can fail with Java's `UNKNOWN` dispatch outcome. The
facade preserves that failure; it does not turn owner closure into a successful
empty result or an automatic retry.

Here `config` selects an existing server with a session. The outer resource
owns Java. The inner Scala scope borrows it, and the original client still
answers after that scope ends:

<!-- snippet: scala-io: ownership-borrowed-client -->
```scala
import _root_.cats.effect.{IO, Resource}
import io.github.libtmux.{Server => JavaServer}
import io.github.libtmux.scaladsl.cats.{Server => ScalaServer}

Resource
  .make(IO.blocking(JavaServer.open(config)))(java => IO.blocking(java.close()))
  .use { java =>
    ScalaServer.fromJava[IO](java).use { server =>
      server.sessions.flatMap(values => IO(assert(values.nonEmpty)))
    }.flatMap(_ => IO.blocking(assert(java.isAlive())))
  }
```

## Java identity and escape hatches

Operational [handles][handles] retain their original Java handles and delegate
equality and hash codes to them. A pane's identity is physical; a window's
identity includes its captured session and window index. Borrowing does not
replace that identity by reacquiring the same textual ID. Refresh follows the
Java contract and can return a pane through a different window occurrence.

`unsafeJava` returns the underlying Java object without transferring
ownership. It is the escape from the Scala scope checks and effect scheduling.
Code using it must follow the Java client's ownership and threading contracts.
Keeping `unsafeJava` from an owned scope does not keep that client open.
Keeping it from a borrowed scope does not make the Scala facade its owner.

## Control attachments

[`Control.attach`][control] on a captured session owns a separate process
attachment to that capture's process, started by the session's transport.
`Control.attachUnfenced` on a config and session id does not check the process.
Releasing either stops its admitted requests before closing the attachment and
preserves the daemon.

Acquire an output or event subscription inside the attachment's resource and
before starting its producer. Release the subscription before the attachment.
An [observation][observation] allows one active stream consumer; canceled
consumption releases that slot. Releasing the observation closes its Java
subscription and discards buffered events. Keep command and observation
attachments separate when canceling a command must not end observation.

See [execution](execution.md) for admission bounds, cancellation uncertainty,
and the difference between a control acknowledgement and command completion.

[blocking-server]:
  ../src/main/scala/io/github/libtmux/scaladsl/blocking/Server.scala
[cats-server]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Server.scala
[handles]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Handles.scala
[control]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Control.scala
[observation]:
  ../../libtmux-scala-cats/src/main/scala/io/github/libtmux/scaladsl/cats/Observation.scala
