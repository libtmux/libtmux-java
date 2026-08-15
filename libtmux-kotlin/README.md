# libtmux-kotlin

**Kotlin ergonomics over the Java API. Sugar, not enablement.**

You do not need this module to use libtmux from Kotlin. The core is annotated
with [JSpecify](https://jspecify.dev/), which Kotlin has read since 1.5.20, so
every type already arrives as `Window` rather than `Window!`.

`io.github.libtmux:libtmux-kotlin` — not yet on Maven Central.

> **Alpha.** The API will change without notice.

## What already works with no module at all

```kotlin
Server.open(config).use { server ->                        // AutoCloseable
    val session = server.newSession { it.named("build") }  // SAM conversion
    val editors = server.windows()
        .filter(Window_.name().startsWith("edit"))         // FilterExpr is a Predicate
}
```

Make a nullness mismatch an error rather than a warning:

```kotlin
kotlin {
    compilerOptions { freeCompilerArgs.addAll("-Xjspecify-annotations=strict") }
}
```

This module is built that way, which is how the claim stays honest: its `!`
operator did not compile until its type parameter was bounded `T : Any`, because
`@NullMarked` makes the core's `FilterExpr` a `FilterExpr<T : Any>`.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))
    implementation("io.github.libtmux:libtmux-kotlin")
}
```

## What it adds

**Absence as `null`.** `Optional` is inert in Kotlin — `?.`, `?:` and smart casts
do not work on it — so the accessors that can genuinely be absent get a nullable
form:

```kotlin
import io.github.libtmux.kotlin.activeWindowOrNull
import io.github.libtmux.kotlin.floatingOrNull
import io.github.libtmux.kotlin.getOrNull

val window = session.activeWindowOrNull() ?: return
val floats = pane.floatingOrNull()                     // null before tmux 3.7, which cannot report it
val status = server.options().getOrNull("status-left")
```

**Negation as an operator:**

```kotlin
import io.github.libtmux.kotlin.not

val idle = server.panes().filter(!Pane_.active().isTrue())
```

There is deliberately no `and`/`or` here. Those are already methods on
`FilterExpr`, and an extension of the same name would be shadowed by the member
inside its own body while winning at an infix call site — correct either way, for
a reason no reader should have to work out. `a.and(b)` is one character longer
than `a and b` and always means what it appears to.

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
