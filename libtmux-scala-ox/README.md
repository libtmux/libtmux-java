# libtmux-scala-ox

**An Ox `Flow` over libtmux subscriptions and live views, for structured
concurrency on virtual threads.**

This optional module adds no handle type: the direct-style facade's opaque
handles work unchanged inside an Ox `supervised` scope and its `fork`s. What it
adds is a cold `Flow` for the two things that push rather than answer: a control
client's subscription, and a live view of a server.

<!-- snippet: scala-build: ox-readme-install -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-ox" % "<version>"
```

From Gradle or Maven the coordinate is `io.github.libtmux:libtmux-scala-ox_3`.
None is on Maven Central yet; the Scala artifacts publish with the Java ones.

**This project is alpha.** Releases carry an `-alpha` prerelease tag. The API is
not settled, and any release may change or remove exported identifiers without a
deprecation period. Pin an exact version rather than a range. Not recommended
for production.

## Flows

`Flows.subscription(sub)` emits one element per step of an `EventSubscription`:
an event it kept, or a gap naming how many were lost. Collecting it takes the
subscription for good and closes it when the collection ends, so a second
collection is refused as it starts rather than splitting one gap-bearing
sequence between two readers, or closing the subscription under the first.

`Flows.liveView(view)` emits the current view of a server when the flow starts,
then every newer one, so a change published between reading a view and
collecting the flow is not missed.

Both are cold: nothing is read until the flow runs, and the flow's scope owns
the reading.

## Documentation

The [Scala guides](../docs/guide/scala/getting-started.md) cover installation
and the facades' contracts; [streaming](../docs/guide/scala/streaming.md)
covers subscriptions, loss and cancellation. Source: [`Flows`][flows].

[flows]:
  src/main/scala/io/github/libtmux/scaladsl/ox/Flows.scala
