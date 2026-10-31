<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Scala">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-scala-ox

**An Ox `Flow` over libtmux subscriptions and live views, for structured
concurrency on virtual threads.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/scala/)
[![libtmux-scala-ox_3](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-scala-ox_3?label=libtmux-scala-ox_3)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-scala-ox_3)

</div>

[`Flows.liveView`][flows] and [`Flows.subscription`][flows] turn pushed events
into a cold `Flow`: a live view of a server, and a control client's
subscription. Acquiring either requires tmux 3.7+ and a carrier-owned pinned
socket; ordinary direct-style operations keep the core 3.3a minimum. The
facade's handles work unchanged in an Ox `supervised` scope.

`io.github.libtmux:libtmux-scala-ox_3` — on Maven Central, released with the
Java artifacts.
API reference: [`Flows`][flows] on libtmux.org for the current trunk,
[javadoc.io][javadoc] for a released version.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.

**Contents** — [Quick start](#quick-start) · [What it leaves
out](#what-it-leaves-out) · [Support](#support-and-status) ·
[Documentation](#documentation)

## Quick start

<!-- snippet: scala-build: ox-readme-install -->
```sbt
libraryDependencies += "io.github.libtmux" %% "libtmux-scala-ox" % "<version>"
```

From Gradle or Maven the coordinate is `io.github.libtmux:libtmux-scala-ox_3`.

<a id="flowsliveview-the-current-view-then-every-newer-one"></a>

### [`Flows.liveView`][flows]: the current view, then newer views

It emits the current view when collection starts, then the latest newer view
available at each read. Several publications can coalesce while the consumer
is busy; this is a live state view, not a history of every change. Here
`config` selects an existing tmux server:

<!-- snippet: scala-sync-control: ox-readme-live-view -->
```scala
import _root_.ox.{supervised, useCloseableInScope}
import io.github.libtmux.scaladsl.{config => _, *}
import io.github.libtmux.scaladsl.live.LiveView
import io.github.libtmux.scaladsl.ox.Flows

supervised {
  val server = useCloseableInScope(Server.open(config))
  val live = useCloseableInScope(LiveView.attach(server.sessions().head))
  val first = Flows.liveView(live).take(1).runToList()
  assert(first.head.snapshot.sessions().size() == 1)
}
```

It runs against a real tmux in the documentation suite. Both flows are cold:
nothing is read until the flow runs.

[`WatchWithOx`][example] is a runnable program: a supervised fork collects a
live view's flow while the main body adds a window.

### [`Flows.subscription`][flows]: a subscription step by step

It emits one element per step of an [`EventSubscription`][subscription]: an
event it kept, or a gap naming how many were lost.

- Collecting it takes the subscription for good and closes it when the
  collection ends.
- A second collection is refused as it starts. Otherwise two readers would
  split one sequence, gaps included, or the second would close the subscription
  under the first.

## What it leaves out

- **A handle type.** The facade's opaque handles work unchanged here.
- **Hot flows.** Nothing is read until the flow runs.
- **Sharing a subscription.** A second collection of one is refused.

## Support and status

- **Scala 3 on virtual threads**, with the same JDK and tmux range as
  [`libtmux-scala`](../libtmux-scala/README.md).
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.

## Documentation

- The [Scala guides](../docs/guide/scala/getting-started.md) cover installation
  and the facades' contracts.
- [Streaming](../docs/guide/scala/streaming.md) covers subscriptions, loss and
  cancellation.
- Source: [`Flows`][flows-source].

[example]:
  ../examples/src/main/scala/io/github/libtmux/scaladsl/examples/WatchWithOx.scala
[flows]: https://libtmux.org/en/scala/latest/reference/io-github-libtmux-scaladsl-ox-flows/
[flows-source]:
  src/main/scala/io/github/libtmux/scaladsl/ox/Flows.scala
[javadoc]: https://javadoc.io/doc/io.github.libtmux/libtmux-scala-ox_3
[subscription]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-eventsubscription-eventsubscription/
