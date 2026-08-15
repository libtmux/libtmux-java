# Kotlin

## The core is already null-safe from Kotlin

Not because anything was written for Kotlin, but because the Java is annotated
with [JSpecify](https://jspecify.dev/) and Kotlin has read those annotations
since 1.5.20. Every type in `io.github.libtmux` arrives as `Window`, not
`Window!`, so the compiler knows what can be absent and what cannot.

Turn a mismatch into an error rather than a warning:

```kotlin
kotlin {
    compilerOptions { freeCompilerArgs.addAll("-Xjspecify-annotations=strict") }
}
```

`libtmux-kotlin` is built that way, which is how the claim is kept honest: its
`!` operator would not compile with an unbounded `T`, because `@NullMarked`
makes the core's `FilterExpr` a `FilterExpr<T : Any>`.

## What already works with no module at all

```kotlin
Server.open(config).use { server ->            // AutoCloseable
    val session = server.newSession { it.named("build") }   // SAM conversion
    val editors = server.windows()
        .filter(Window_.name().startsWith("edit"))          // FilterExpr is a Predicate
}
```

## What libtmux-kotlin adds

```kotlin
implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))
implementation("io.github.libtmux:libtmux-kotlin")
```

**Absence as `null`.** `Optional` is inert in Kotlin — `?.`, `?:` and smart casts
do not work on it — so the accessors that can genuinely be absent get a nullable
form:

```kotlin
val window = session.activeWindowOrNull() ?: return
val floats = pane.floatingOrNull()        // null before tmux 3.7, which cannot report it
val status = server.options().getOrNull("status-left")
```

**Negation as an operator:**

```kotlin
val idle = server.panes().filter(!Pane_.active().isTrue())
```

There is deliberately no `and`/`or` here — see `Filters.kt` for why an extension
of the same name as an existing method is a resolution puzzle nobody should have
to solve.

## Why the sugar is downstream and stays there

Nothing written in Java may depend on `libtmux-kotlin`, and the build fails if it
does. Per the JSpecify specification a class carrying `@kotlin.Metadata` is *not*
null-marked, because the Kotlin compiler does not yet emit full nullness into
binaries ([KT-47417](https://youtrack.jetbrains.com/projects/KT/issues/KT-47417/Emit-jspecify-annotations-for-types-in-Kotlin-binaries)).
A Kotlin-authored API would therefore be worse for a Java caller and invisible to
NullAway. The dependency runs one way only.
