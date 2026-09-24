# libtmux-kotlin

**Kotlin ergonomics over the Java API. Sugar, not enablement.**

You do not need this module to use libtmux from Kotlin. The core is annotated
with [JSpecify](https://jspecify.dev/), which Kotlin has read since 1.5.20, so
every type already arrives as `Window` rather than `Window!`.

`io.github.libtmux:libtmux-kotlin` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-kotlin).

> **Alpha.** The API will change without notice.

Needs Kotlin 2.1 or later. The module is compiled for Kotlin 2.2 metadata and
the 2.2 standard library, the level kotlinx-coroutines 1.11 is compiled for, and
a Kotlin compiler reads metadata up to one minor version newer than itself.
`ConsumerBaselineTest` fails if a build raises that level.

Every Kotlin example below is executed against a real tmux server by
[`ReadmeExamplesTest`](src/test/kotlin/io/github/libtmux/kotlin/ReadmeExamplesTest.kt),
one test per section.

## What already works with no module at all

```kotlin
// Given: config: ServerConfig
Server.open(config).use { server ->                        // AutoCloseable
    val session = server.newSession { it.named("build") }  // SAM conversion

    session.name()                       // → build
    server.sessions().size               // → 2
}
```

Make a nullness mismatch an error rather than a warning:

<!-- snippet: skip: build configuration, not library code -->
```kotlin
kotlin {
    compilerOptions { freeCompilerArgs.addAll("-Xjspecify-annotations=strict") }
}
```

This module is built that way, which is how the claim stays honest: its `!`
operator did not compile until its type parameter was bounded `T : Any`, because
`@NullMarked` makes the core's `FilterExpr` a `FilterExpr<T : Any>`.

## Install

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.14"))
    implementation("io.github.libtmux:libtmux-kotlin")
}
```

## What it adds

**Absence as `null`.** `Optional` is inert in Kotlin — `?.`, `?:` and smart casts
do not work on it — so the accessors that can genuinely be absent get a nullable
form:

```kotlin
// Given: session: Session, window: Window, pane: Pane
import io.github.libtmux.kotlin.activePaneOrNull
import io.github.libtmux.kotlin.activeWindowOrNull
import io.github.libtmux.kotlin.floatingOrNull
import io.github.libtmux.kotlin.getOrNull

// A window id, or null once the session has gone.
session.activeWindowOrNull()?.id()?.value()?.startsWith("@")   // → true

// The pane tmux had active in that window, or null when the capture marked none.
session.windows()[0].activePaneOrNull()?.id()?.value()

// A Boolean on tmux 3.7 and later, and null before it, which cannot report the
// flag at all. Absence and false are different answers, and this keeps them so.
pane.floatingOrNull()

// Absent rather than Optional.empty, so ?: and ?. work on it.
server.options().getOrNull("no-such-option")                   // → null
```

**Filtering with an expression.** Kotlin's own `filter` takes a function, not a
`Predicate`, so handing it a `FilterExpr` does not compile — the one place where
reading the Java documentation and writing Kotlin part company. This module adds
the overload:

```kotlin
import io.github.libtmux.kotlin.filter

val editors = server.panes().filter(Pane_.command().startsWith("nvim"))
```

**Negation as an operator:**

```kotlin
import io.github.libtmux.kotlin.filter
import io.github.libtmux.kotlin.not

val idle = server.panes().filter(!Pane_.active().isTrue())
```

There is deliberately no `and`/`or` here. Those are already methods on
`FilterExpr`, and an extension of the same name would be shadowed by the member
inside its own body while winning at an infix call site — correct either way, for
a reason no reader should have to work out. `a.and(b)` is one character longer
than `a and b` and always means what it appears to.

**Lists stay read-only.** A Java `List` is a `MutableList` in Kotlin, so
`add` is on the type even when the capture throws. `readOnly()` is a Kotlin
`List` of the same elements, and it does not offer `add`.

**A subscription as a `Flow`.** `deliveries()` emits a `Delivery.Gap` where the
buffer discarded events, then the events that remain. `kept()` fails that
read. Collecting the flow
closes the subscription. Cancelling the collection does the same. Neither
reconnects, and neither undoes a command tmux has already accepted.
`awaitDelivery` waits for one step and leaves the subscription open. This
module depends on kotlinx-coroutines; the core does not. `await` and
`awaitText` suspend on `kotlin.time.Duration`. Cancelling one interrupts
the wait, which is not a timeout. The public binary surface is the dump
in `api/`, and the build fails when that dump changes.

## Why nothing in Java may depend on this

The build fails if it does. Per the JSpecify specification a class carrying
`@kotlin.Metadata` is **not** null-marked, because the Kotlin compiler does not
yet emit full nullness into binaries
([KT-47417](https://youtrack.jetbrains.com/projects/KT/issues/KT-47417/Emit-jspecify-annotations-for-types-in-Kotlin-binaries)).
A Kotlin-authored API would therefore be worse for a Java caller and invisible to
NullAway. The dependency runs one way only.

## Next

- [Kotlin guide](../docs/guide/kotlin.md)
- [`libtmux`](../libtmux/) — the API this sweetens
- [Root README](../README.md)
