# Changelog

Notable changes to `libtmux-kotlin`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

The modules are released together at one version, so every release since this
module first shipped has a section here. Changes that span modules, such as
documentation, CI and release tooling, are in the [main
changelog](../CHANGELOG.md).

## Unreleased

## 0.1.1 — 2026-11-01

No entries for `libtmux-kotlin`.

## 0.1.0 — 2026-10-25

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.19 — 2026-10-24

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.18 — 2026-10-19

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.16 — 2026-09-26

### Added

- **`SessionBuilder`, `WindowBuilder` and `SplitBuilder` gain `env(name,
  value)`.** The Kotlin DSL now matches every environment variable the Java
  `SessionSpec.Builder`/`WindowSpec.Builder`/`SplitSpec.Builder` can set on the
  new session, window, or pane's process. (#23)

### Changed

- **`libtmux-kotlin`'s subsystem accessors answer wrapper classes, not the raw
  Java handle.** `Pane.options()`, `Server.hooks()`, `Server.shell()`,
  `Server.commands()`, `Server.buffers()`, `Session.environment()`,
  `Server.messageLog()`, `Server.prompt()`, `Server.keys()`,
  `Server.batch()`/`Pane.batch()`, and `Server.chain()` used to answer the raw
  Java type, so calling `.get()`/`.set()` on it ran blocking tmux I/O on the
  caller's coroutine thread outside `ExecutionPolicy`. Every operation on the
  new wrapper classes that reaches tmux is `suspend`, dispatched through
  `ExecutionPolicy.commands` like every other Kotlin operation. (#23)

## 0.0.1-alpha.15 — 2026-09-26

### Added

- **Kotlin: `newSession { window { split { } } }` declares a session's
  shape.** The first `window { }` block names, places and starts tmux's own
  first window; each later one adds a window; `running(...)` starts a
  command. (#23)

- **Kotlin: `ControlClient.output` and `events` are cold `Flow`s.** They
  hold no thread while waiting, and the trailing `onSubscribed` block runs
  once the subscription is open, so a command whose output is wanted cannot
  race it: `control.output(32) { control.send("send-keys", ...) }`. (#23)

- **Kotlin: `Server.liveState` is a `StateFlow` over `ServerMirror`.**
  `withLiveState(session) { live -> ... }` ends it with the block. (#23)

- **Kotlin: query fields live on the handle's companion.** `Pane.command`,
  `Window.panes`. `server.session(expr)` throws unless exactly one matches;
  `sessionOrNull(expr)` is null on none, and `OptionalInt.orNull()` and
  `OptionalLong.orNull()` read the core's optional numbers.
  `retryIfSafe(times) { }` retries only what `safeToRetry()` allows. (#23)

- **Kotlin: `ExecutionPolicy` says where suspend calls run.** `commands` is
  sized from the server's `maxConcurrentCommands`, `streamReads` backs
  `liveState`, and `defaultDeadline` bounds every call. `withServer { }` and
  `withControl { }` close what they open when the block ends, cancelled or
  not. (#23)

### Changed

- **Kotlin reads the core's collections as read-only.** A list, set or map
  the core returns is a Kotlin `List`, `Set` or `Map`, so code that called
  `add` on one no longer compiles; the call threw before. See [migration
  guidance](../MIGRATION.md#kotlin-reads-core-collections-as-read-only). (#23)

- **`libtmux-kotlin` is wrapper classes, not extensions on the Java types.**
  `Server`, `Session`, `Window`, `Pane`, `Client` and `ControlClient` in
  `io.github.libtmux.kotlin` make every tmux operation `suspend` and every
  captured value a property; each answers its Java handle as `asJava`, and
  `Server.fromJava` wraps one opened in Java. It is readable from Kotlin 2.1.
  See [migration
  guidance](../MIGRATION.md#libtmux-kotlin-is-wrapper-classes-now-not-extensions-on-the-java-types).
  (#23)

### Removed

- **The Kotlin extensions on the Java types,** such as
  `Session.activeWindowOrNull` and `Options.getOrNull`. The wrapper classes'
  members replace them. (#23)

## 0.0.1-alpha.14 — 2026-09-20

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.13 — 2026-09-20

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.12 — 2026-09-19

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.11 — 2026-09-12

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.10 — 2026-09-06

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.9 — 2026-09-06

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.8 — 2026-08-30

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.7 — 2026-08-22

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.6 — 2026-08-16

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.5 — 2026-08-16

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.3 — 2026-08-16

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.2 — 2026-08-16

No entries for `libtmux-kotlin`.

## 0.0.1-alpha.1 — 2026-08-16

### Added

- **`libtmux-kotlin` gained the `filter` overload Kotlin actually needs.** Kotlin's
  own `filter` takes a function rather than a `Predicate`, so passing a
  `FilterExpr` to it did not compile — the documentation had claimed it did.

- **`libtmux-kotlin`.** The core was already null-safe from Kotlin — it is
  annotated with JSpecify, which Kotlin has read since 1.5.20 — so this is what
  Java cannot express: absence as `null` rather than `Optional`, and `!expr` on
  a filter. Built with `-Xjspecify-annotations=strict`, which is how the claim
  stays honest: `!` did not compile until its type parameter was bounded `T : Any`,
  because `@NullMarked` makes the core's `FilterExpr` a `FilterExpr<T : Any>`.
  Nothing written in Java may depend on it, and the build fails if that changes.
