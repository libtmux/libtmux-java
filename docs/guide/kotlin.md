# Kotlin

## Wrapper classes hold the Java handle privately

`Server`, `Session`, `Window`, `Pane`, `Client`, and `ControlClient` in
`io.github.libtmux.kotlin` are hand-written Kotlin classes, not the Java types
directly and not extensions on them. A same-named extension is silently
shadowed by a Java member with only a compiler warning, so a member on a real
wrapper class is what makes a generated suspend mirror safe to add later
without an accidental collision.

Every operation that may contact tmux is `suspend`. Captured state — an id, a
name, a size — is a plain, non-suspending property. Query fields live on the
handle type's companion (`Pane.command`), never as an instance member, so
`Pane.command` (the field) and `pane.currentCommand` (the captured value)
never collide.

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val session = server.newSession("guide-demo")

    session.name                          // → guide-demo
}
```

## `ExecutionPolicy`: two independently sized pools

`ExecutionPolicy.commands` backs every `suspend` operation this module
generates or hand-writes, sized from `ServerConfig.maxConcurrentCommands()` —
the transport's real admission bound, not a guessed constant.
`ExecutionPolicy.streamReads` backs only what still genuinely blocks a
thread: `Server.liveState`'s background pump. `ControlClient.output`/`events`
hold no thread from either pool; they are built on the non-blocking
`EventSubscription.poll`/`onReady` path, not a parked `next()`.

`ExecutionPolicy.default(config)` sizes both pools; pass a differently-sized
one to `Server.open` or `withServer` when a program opens more than the
default 16 concurrent `liveState` watches.

## The cold `Flow` bridge, and why it is not `callbackFlow`

`ControlClient.output`/`events` return a plain `flow {}` that calls
`EventSubscription.poll()` from the collector itself, suspending on the
subscription's one-shot `onReady` callback via `suspendCancellableCoroutine`
when nothing is buffered, and `clearReady()` on cancellation. A fresh Java
subscription opens per `collect()`, so two concurrent collections never share
one subscription's buffer or gap accounting.

A `callbackFlow` over the same subscription would have its readiness callback
drain `poll()` straight into the flow's channel, where a full channel's
`trySend` failing discards the polled item with no `Gap` recorded. `flow {}`
calls `poll()` only from the collector itself, so an overflow can only happen
inside the subscription's own buffer, which is exactly what turns into a
`Gap`.

## Blocking calls from a coroutine

Every libtmux call blocks its thread until tmux answers. This module already
wraps each one in `runInterruptible`, dispatched on `ExecutionPolicy.commands`
or `.streamReads`, so cancelling the coroutine interrupts the wait rather than
leaving it running past the point nothing is listening for its result.

- An ordinary suspend call — `newSession`, `capture`, `sendLine` — already
  runs on `policy.commands`, sized from the server's own admission bound.
- `Server.liveState`'s background pump runs on `policy.streamReads`.
- `ControlClient.output`/`events` hold no thread at all; only collecting the
  returned `Flow` reads.

## The DSL, and the compile error the first draft had

`@DslMarker` marks `SessionBuilder`, `WindowBuilder`, and `SplitBuilder` so an
inner block cannot reach an outer block's receiver by accident. Directory is
settable at all three levels for exactly this reason: writing `directory = x`
inside a nested `split { }` resolves to the innermost builder, and reaching
the outer one on purpose needs `this@newSession.directory = x` written out.

```kotlin
// Given: config: ServerConfig
withServer(config) { server ->
    val session = server.newSession {
        name = "layout-demo"
        window { name = "editor" }
        window {
            name = "shell"
            split { toRight(); percent(30) }
        }
    }

    session.windows.size                  // → 2
}
```

tmux's `SplitSpec.Builder` spells direction and size as method calls —
`below()`/`above()`/`toRight()`/`toLeft()`, `cells(n)`/`percent(n)` — not an
enum. The DSL forwards to that real vocabulary directly rather than inventing
a parallel `SplitDirection`/`PaneSize` type.

## Why nothing in Java may depend on this

Nothing written in Java may depend on `libtmux-kotlin`, and the build fails if
it does. Per the JSpecify specification a class carrying `@kotlin.Metadata` is
*not* null-marked, because the Kotlin compiler does not yet emit full
nullness into binaries
([KT-47417](https://youtrack.jetbrains.com/projects/KT/issues/KT-47417/Emit-jspecify-annotations-for-types-in-Kotlin-binaries)).
A Kotlin-authored API would therefore be worse for a Java caller and invisible
to NullAway. The dependency runs one way only.

See the [module README](../../libtmux-kotlin/README.md) for the full call-site
tour, including the query DSL, the exhaustive `when` over sealed failures, and
`StateFlow`.
