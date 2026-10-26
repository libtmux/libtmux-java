<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Kotlin">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-kotlin

**Coroutine wrapper classes, a session/window/split DSL, and `Flow`/`StateFlow`
bridges over the Java API.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/kotlin/)
[![libtmux-kotlin](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-kotlin?label=libtmux-kotlin)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-kotlin)

</div>

Drive tmux from Kotlin: [`withServer`][with-server] opens a server and closes
it, the [`newSession`][server-newsession] DSL declares a session with its
windows and splits, and [`Pane.run`][pane] runs a command to its exit status.
Every operation that reaches tmux is `suspend`.

`io.github.libtmux:libtmux-kotlin` — [on Maven
Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-kotlin).
API reference: [libtmux.org][reference] for the current trunk,
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-kotlin) for a
released version.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.

**Contents** — [Quick start](#quick-start) · [Declare a
session](#declare-a-session-its-windows-and-their-splits) · [Send
keys](#send-keys-capture-output-and-run-a-command) ·
[Query](#query-with-typed-fields) · [Handle failures](#handle-failures) · [Watch
tmux](#watch-tmux-change) · [Leaves out](#what-it-leaves-out) ·
[Support](#support-and-status)

## Quick start

Add it through the BOM, which names one version for every module:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.1.0"))
    implementation("io.github.libtmux:libtmux-kotlin")
}
```

### [`withServer`][with-server]: open a server, close it however the block ends

[`Server`][server], [`Session`][session], [`Window`][window], [`Pane`][pane],
[`Client`][client] and [`ControlClient`][control-client] here are Kotlin classes
over the matching Java handle. `withServer` closes the server even if the block
throws or is cancelled:

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val session = server.newSession("build")

    session.name                          // → build
    server.admissionBound > 0             // → true
}
```

It runs against a real tmux, and every value after a `→` is asserted.

- Each class answers its Java handle as `asJava`, and `Server.fromJava` wraps a
  server opened in Java.
- Captured state is a plain property.
- A subsystem an accessor answers, such as [`Pane.options()`][options],
  [`Server.hooks()`][hooks] and [`Server.keys()`][keys], is wrapped the same
  way, so calling into it never blocks the caller's thread outside
  [`ExecutionPolicy`][execution-policy].

## Declare a session, its windows, and their splits

### [`Server.newSession`][server-newsession]: the DSL

[`SessionBuilder`][session-builder], [`WindowBuilder`][window-builder] and
[`SplitBuilder`][split-builder] nest, so the code reads like the layout:

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val session = server.newSession {
        name = "editors"
        window { name = "left" }
        window {
            name = "right"
            split { toRight(); percent(30) }
        }
    }

    session.windows.size                  // → 2
}
```

- `@DslMarker` keeps a nested block from reaching an outer block's receiver by
  accident. A `split { }` inside a `window { }` cannot set the session's
  `directory` without writing `this@newSession.directory`.
- tmux always gives a new session one window. The first `window { }` block
  renames it, and each later block is a new window, so two blocks make two
  windows, not three.

## Send keys, capture output, and run a command

### [`Pane.run`][pane]: a command to its exit status

[`Pane`][pane] types a line, waits for text, captures the screen, and runs a
command to its end. `orNull` turns the exit status's [`OptionalInt`][ornull]
into a Kotlin nullable:

```kotlin
// Given: config: ServerConfig
import io.github.libtmux.kotlin.orNull
import kotlin.time.Duration.Companion.seconds

withServer(config) { server ->
    val session = server.newSession("keys-demo")
    val pane = session.activeWindow?.activePane ?: error("no active pane")

    pane.sendLine("echo ready")
    pane.awaitText("ready", timeout = 5.seconds)
    val lines = pane.capture()

    val run = pane.run("echo hi && exit 3", timeout = 5.seconds)
    run.exitStatus.orNull()                // → 3
    lines.isNotEmpty()                    // → true
}
```

## Query with typed fields

### [`Pane.command`][text-field]: fields live on the companion

Fields live on the handle type's companion: `Pane.command`, where Java has the
static `Pane_.command()`. They are generated from the same `field-catalog.tsv`
that generates the Java metamodel, so the two cannot disagree:

```kotlin
// Given: config: ServerConfig
import io.github.libtmux.kotlin.query.active
import io.github.libtmux.kotlin.query.command

withServer(config) { server ->
    server.newSession("query-demo")
    val editors = server.panes(Pane.command startsWith "nvim")
    val activePanes = server.panes(Pane.active.isTrue())

    editors.size >= 0                     // → true
    activePanes.isNotEmpty()              // → true
}
```

- `server.session(expr)` throws [`CardinalityException`][cardinality] on no
  match or more than one.
- `server.sessionOrNull(expr)` is null on no match and still throws on more than
  one. That is Kotlin's collection convention (`single` and `singleOrNull`),
  applied to a tmux lookup.
- [`FlagField.isTrue`][is-true] and the text operators build the expression.

## Handle failures

### [`LibTmuxException`][libtmux-exception]: an exhaustive `when`

Every failure the library raises is one sealed tree, so a `when` over it needs
no `else`:

```kotlin
import io.github.libtmux.exception.CardinalityException
import io.github.libtmux.exception.CommandRejectedException
import io.github.libtmux.exception.ControlEndedException
import io.github.libtmux.exception.DispatchException
import io.github.libtmux.exception.LibTmuxException
import io.github.libtmux.exception.MalformedResponseException
import io.github.libtmux.exception.ServerUnavailableException
import io.github.libtmux.exception.TargetGoneException
import io.github.libtmux.exception.UnencodableTextException
import io.github.libtmux.exception.UnsupportedFeatureException

fun nextStep(failure: LibTmuxException): String =
    when (failure) {                                // exhaustive, no else
        is TargetGoneException -> "look it up again"
        is ServerUnavailableException -> "start a server"
        is CommandRejectedException -> "change the request"
        is DispatchException -> if (failure.safeToRetry()) "send it again" else "read state first"
        is ControlEndedException -> "attach again"
        is UnsupportedFeatureException ->
            if (failure.belowFloor()) "upgrade tmux" else "do without"
        is UnencodableTextException -> "use a UTF-8 locale"
        is MalformedResponseException -> "report it"
        is CardinalityException.NoMatch -> "nothing matched"
        is CardinalityException.MultipleMatches -> "ambiguous"
    }

nextStep(CardinalityException.MultipleMatches("many", 3))   // → ambiguous
```

A new leaf breaks every such `when` at the branch that omits it:
`ExhaustivenessCompileTest` compiles a `when` one branch short and asserts the
compiler rejects it.

### [`retryIfSafe`][retry-if-safe]: retry only when it is safe

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val version = retryIfSafe(times = 3) { server.version() }
    version.major >= 3                    // → true
}
```

- It catches only [`DispatchException`][dispatch] and checks
  `failure.safeToRetry()`, which the operation that threw it computed from its
  own catalogued idempotence.
- There is no retry executor, and no catalog lookup at the call site.

## Watch tmux change

### [`ControlClient.output`][control-client]: a subscription as a cold `Flow`

[`withControl`][with-control] attaches a control client, and `output` turns a
[`Delivery`][delivery] stream into a `Flow`:

Managed control requires tmux 3.7+ and a carrier-owned pinned socket.
Ordinary suspend operations retain the core 3.3a minimum.

<!-- snippet: control: subscriptions require managed control -->
```kotlin
// Given: config: ServerConfig
import io.github.libtmux.control.Delivery
import kotlinx.coroutines.flow.first

withServer(config) { server ->
    val session = server.newSession("flow-demo")
    withControl(server, session) { control ->
        val step = control.output(capacity = 64) {
            control.send("send-keys", "-t", session.name, "echo flowed", "Enter")
        }.first()
        val outcome = when (step) {            // exhaustive, no else
            is Delivery.Event -> "kept"
            is Delivery.Gap -> "lost ${step.missed}"
        }
        outcome                                // → kept
    }
}
```

- `output` and `events` open a fresh Java [`EventSubscription`][subscription]
  per `collect()`, never a parked thread, and close it when collection ends, is
  cancelled, or throws.
- Output tmux sends before the subscription opens is not delivered. Put the
  command whose output you want in the trailing `onSubscribed` block, which runs
  once the subscription exists and before the first read.
- A `Delivery.Gap` is an element like any other, ahead of the events that
  survived a full buffer. Nothing is silently dropped.

### `Server.withLiveState`: the live server as a `StateFlow`

<!-- snippet: control: subscriptions require managed control -->
```kotlin
// Given: config: ServerConfig
import kotlinx.coroutines.flow.first

withServer(config) { server ->
    val session = server.newSession("live-demo")
    server.withLiveState(session) { live ->
        val view = live.first()

        view.epoch >= 0L                  // → true
    }
}
```

- It wraps Java's [`ServerMirror`][server-mirror], so the resnapshot after a
  notification, a gap or a reconnect happens once, inside it.
- It is named `liveState` so it does not collide with the Java type it wraps.
- `liveState`'s background pump runs until its scope ends, so a caller
  collecting for a program's whole life passes its own long-lived scope.
- `withLiveState` is the scoped form for everything shorter: it cancels the pump
  once its block returns, as `withServer` and `withControl` do for the resources
  they open.

## What it leaves out

- **Java depending on it.** The build fails if anything in Java does: a class
  carrying `@kotlin.Metadata` is not null-marked under JSpecify
  ([KT-47417][kt-47417]), so the dependency runs one way only.
- **A retry executor.** [`retryIfSafe`][retry-if-safe] is a function, with no
  catalog lookup at the call site.

## Support and status

- **Kotlin 2.1 or later.** The module is compiled for Kotlin 2.2 metadata and
  the 2.2 standard library, the level kotlinx-coroutines 1.11 is compiled for.
  A Kotlin compiler reads metadata up to one minor version newer than itself.
- **A guarded floor.** `ConsumerBaselineTest` fails if a build raises that
  level.
- **Examples run.** Every Kotlin example above is executed against a real tmux
  server by
  [`ReadmeExamplesTest`](src/test/kotlin/io/github/libtmux/kotlin/ReadmeExamplesTest.kt),
  one test per section.
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.

## Documentation

- [Kotlin guide](../docs/guide/kotlin.md)
- [`libtmux`](../libtmux/README.md) — the API this wraps
- [Root README](../README.md)

[cardinality]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-cardinalityexception-cardinalityexception/
[client]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-client/
[control-client]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-controlclient/
[delivery]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-delivery-delivery/
[dispatch]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-dispatchexception-dispatchexception/
[execution-policy]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-executionpolicy/
[hooks]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-hooks/
[is-true]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-query-flagfield-istrue/
[keys]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-keys/
[kt-47417]: https://youtrack.jetbrains.com/projects/KT/issues/KT-47417/Emit-jspecify-annotations-for-types-in-Kotlin-binaries
[libtmux-exception]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-exception-libtmuxexception-libtmuxexception/
[options]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-options/
[ornull]: https://libtmux.org/en/kotlin/latest/reference/java-util-optionalint-ornull/
[pane]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-pane/
[reference]: https://libtmux.org/en/kotlin/latest/reference/
[retry-if-safe]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-retryifsafe/
[server]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-server/
[server-mirror]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-snapshot-servermirror-servermirror/
[server-newsession]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-server-newsession/
[session]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-session/
[session-builder]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-sessionbuilder/
[split-builder]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-splitbuilder/
[subscription]: https://libtmux.org/en/java/latest/reference/io-github-libtmux-control-eventsubscription-eventsubscription/
[text-field]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-query-textfield/
[window]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-window/
[window-builder]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-windowbuilder/
[with-control]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-withcontrol/
[with-server]: https://libtmux.org/en/kotlin/latest/reference/io-github-libtmux-kotlin-withserver/
