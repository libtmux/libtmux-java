# Changelog

Notable changes, newest first. Versions follow [semantic versioning][semver];
until 1.0 the public API may change in a minor release.

[semver]: https://semver.org/spec/v2.0.0.html

## Unreleased

### Fixed

- **A trailing semicolon ended a command under one carrier and not the other.**
  tmux ends a command at a semicolon ending *any* argument, not only at one
  standing alone, and a backslash before it keeps the semicolon. The control
  carrier quoted both, so `server.cmd(List.of("new-window", "-d", "-n",
  "grouped;", "list-windows"))` created a window under `DIRECT` and none under
  `CONTROL`. `ControlClient.isCommandGroup` is now the single reading of that
  rule and both carriers consult it. Measurements in
  [`docs/spikes/21`](docs/spikes/21-command-group-boundaries.md).
- **`ControlClient.send` accepted a request it could not answer.** Control mode
  frames a reply per command, so a group produced several replies for one
  awaited request and the extras were matched to whatever asked next. It now
  refuses a group before writing anything, leaving the stream in step.
- **A control transport closed mid-attach left a tmux client running.** Finding
  a session takes a command of its own, and a close landing in that window could
  not see a client that did not exist yet. The attach now checks on both sides
  and releases what it made.
- **`VirtualThreadTransport` could return `null`.** It rescued
  `RuntimeException` only, so an `Error` on the worker killed the thread with
  nothing recorded and the join returned no result and no failure.
- **A `%output` listener that threw ended the reader thread**, which is also the
  only thread that resolves replies, so every later request timed out for a
  reason belonging to somebody else's callback. Listener failures now go to the
  thread's uncaught-exception handler and the remaining listeners still run.

### Added

- **`libtmux-kotlin`.** The core was already null-safe from Kotlin — it is
  annotated with JSpecify, which Kotlin has read since 1.5.20 — so this is what
  Java cannot express: absence as `null` rather than `Optional`, and `!expr` on
  a filter. Built with `-Xjspecify-annotations=strict`, which is how the claim
  stays honest: `!` did not compile until its type parameter was bounded `T : Any`,
  because `@NullMarked` makes the core's `FilterExpr` a `FilterExpr<T : Any>`.
  Nothing written in Java may depend on it, and the build fails if that changes.
- **A guide for [Kotlin](docs/guide/kotlin.md) and [Scala](docs/guide/scala.md)**,
  including why there is no `libtmux-scala` and what shape it would take.
- **`platformCoversEveryPublishedModule`.** A module is published exactly when it
  applies `libtmux.published-library`, and the build now fails when that set
  stops matching `libtmux-bom`, rather than shipping an artifact the platform
  does not manage.
- **`kotlinStaysDownstream`**, which fails when anything not written in Kotlin
  depends on the Kotlin module. Per the JSpecify specification a class carrying
  `@kotlin.Metadata` is not null-marked, so such a dependency would silently cost
  a Java caller its nullness.
- **Teardown that outlives the process meant to do it.** A test JVM killed
  outright left a tmux server running, and once the host's temporary-file
  cleaner removed its directory the socket could not be reached to kill it —
  nineteen such servers were found on this machine. `TmuxExtension` now names
  the owning JVM in the socket path, ends its own servers from a shutdown hook,
  and sweeps servers whose owner is gone before making its first one. A live
  owner is never touched, so Gradle's per-module workers and the matrix's eight
  lanes can share one root. Measurements, and the two rejected designs, in
  [`docs/spikes/22`](docs/spikes/22-abandoned-servers.md).
- **A model can narrow `tmux_list_panes` with a filter.** The MCP server accepts
  the same versioned filter document every port of libtmux reads, so
  `pane_current_command starts_with nvim` selects panes without the model
  reasoning over the whole listing. One capture either way: filtering happens
  over what it already returned. The example in the tool's own description is a
  constant the test suite parses, so it cannot drift from the schema.
- `TmuxTools.describe(Collection<Pane>)`, replacing a filtering overload that
  would have read as though tmux did the selecting.
- `libtmux-bom`, so a consumer names a version once.
- Continuous integration: `check` on JDK 21 and 25, and the real-tmux suite
  against each of the eight supported tmux releases.
- `LICENSE`. The published POM had declared MIT since the first release
  metadata, with nothing in the repository to back it.

### Changed

- The build no longer uses any API removed in Gradle 10.
- Types are named rather than fully qualified inline: 67 occurrences across 26
  files, where the same type was already imported directly above.
- The compile-probe test writes its class files to a directory of its own and
  removes them, instead of leaving them under the root reserved for sockets.
- **The real-tmux suite and the benchmark moved out of `libtmux-junit5`** into
  `integration-tests/` and `benchmarks/`, neither of which is published. A suite
  living in one artifact's test source set made that artifact's dependencies and
  lifecycle answerable for how the whole library is tested, and kept the
  benchmark one forgotten tag away from running in an ordinary build.
