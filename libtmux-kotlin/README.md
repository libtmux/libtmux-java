# libtmux-kotlin

**Coroutine wrapper classes, a session/window/split DSL, and `Flow`/`StateFlow`
bridges over the Java API.**

`io.github.libtmux:libtmux-kotlin` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-kotlin).

> **Alpha.** The API will change without notice.

Needs Kotlin 2.1 or later. The module is compiled for Kotlin 2.2 metadata and
the 2.2 standard library, the level kotlinx-coroutines 1.11 is compiled for, and
a Kotlin compiler reads metadata up to one minor version newer than itself.
`ConsumerBaselineTest` fails if a build raises that level.

Every Kotlin example below is executed against a real tmux server by
[`ReadmeExamplesTest`](src/test/kotlin/io/github/libtmux/kotlin/ReadmeExamplesTest.kt),
one test per section.

## Install

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.14"))
    implementation("io.github.libtmux:libtmux-kotlin")
}
```

## Wrapper classes, not the Java types directly

`Server`, `Session`, `Window`, `Pane`, `Client`, and `ControlClient` here are
Kotlin classes that hold the matching Java handle privately. Every operation
that reaches tmux is `suspend`; captured state is a plain property.
`withServer` opens one and closes it even if the block throws or is
cancelled:

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val session = server.newSession("build")

    session.name                          // → build
    server.admissionBound > 0             // → true
}
```

## Declare a session, its windows, and their splits with the DSL

`@DslMarker` keeps a nested block from reaching an outer block's receiver by
accident: a `split { }` inside a `window { }` cannot set the *session's*
`directory` without writing `this@newSession.directory` to say so. tmux
always gives a new session one window; the first `window { }` block renames
that one, and each later block is a genuine new window — two blocks make two
windows, not three.

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

## Send keys, capture output, run a command

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

## Query with typed fields on the companion

Fields live on the handle type's companion — `Pane.command`, not a static
`Pane_.command()` — generated from the same `field-catalog.tsv` that
generates the Java metamodel, so the two can never disagree.

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

`server.session(expr)` throws `CardinalityException` on no match or more than
one; `server.sessionOrNull(expr)` is null on no match and still throws on
more than one — Kotlin's own collection convention (`single`/`singleOrNull`),
applied to a tmux lookup.

## Exhaustive `when` over the sealed failure tree

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
        is UnsupportedFeatureException -> "do without"
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

## A subscription as a cold `Flow`

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

`output`/`events` open a *fresh* Java subscription per `collect()` — over
`EventSubscription.poll`/`onReady`, never a parked thread — and close it when
collection ends, is cancelled, or throws. Output tmux sends before that
subscription opens is not delivered, so the command whose output you want goes
in the trailing `onSubscribed` block, which runs once the subscription exists
and before the first read. A `Delivery.Gap` is an element like
any other, ahead of the events that survived a full buffer; nothing is
silently dropped. `FlowBridgeStressTest` runs 300,000 events through a bursty
producer against a slow collector and accounts for every one, as delivered or
gapped.

## The live server as a `StateFlow`

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

Wraps Java's `ServerMirror` — resnapshot on notification, on gap, and on
reconnect happen once, inside it — rather than patching state in Kotlin.
Named `liveState`, not `ServerMirror`, so it does not collide with the Java
type it wraps. `liveState`'s own background pump runs until its scope ends,
by design — a caller collecting for a program's whole life passes its own
long-lived scope — so `withLiveState` is the scoped form for everything
shorter: it cancels the pump once its block returns, the same shape
`withServer`/`withControl` already have for the resources they open.

## Retry using the safe-to-retry predicate

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val version = retryIfSafe(times = 3) { server.version() }
    version.major >= 3                    // → true
}
```

`retryIfSafe` catches only `DispatchException` and checks
`failure.safeToRetry()`, computed by the operation that threw it from its own
catalogued idempotence — no retry executor, and no catalog lookup at the call
site.

## Why nothing in Java may depend on this

The build fails if it does. Per the JSpecify specification a class carrying
`@kotlin.Metadata` is **not** null-marked, because the Kotlin compiler does not
yet emit full nullness into binaries
([KT-47417](https://youtrack.jetbrains.com/projects/KT/issues/KT-47417/Emit-jspecify-annotations-for-types-in-Kotlin-binaries)).
A Kotlin-authored API would therefore be worse for a Java caller and invisible to
NullAway. The dependency runs one way only.

## Next

- [Kotlin guide](../docs/guide/kotlin.md)
- [`libtmux`](../libtmux/) — the API this wraps
- [Root README](../README.md)
