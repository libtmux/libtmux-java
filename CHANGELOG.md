# Changelog

Notable changes, newest first. Versions follow [semantic versioning][semver].

**This project is alpha.** Every version carries an `-alpha` qualifier, which is
the lowest Maven's comparator recognises, and a `0.0.x` number. Both say the same
thing: anything below may change in any release, including in ways that do not
compile. Only the newest version is supported, and there are no backports. Pin an
exact version rather than a range.

[semver]: https://semver.org/spec/v2.0.0.html

## Unreleased

## 0.0.1-alpha.5 — 2026-08-16

### Added

- **`libtmux-mcp` can wait, so an agent does not have to poll.** `tmux_run`
  sends a command, waits for it on a private tmux channel, and returns its output
  with an exit status in one call; `tmux_wait_for_text` watches output nobody here
  started, with stop patterns so a run that fails is not waited on to the
  deadline; `tmux_wait_for_channel` blocks inside tmux and infers nothing from the
  screen. Every wait is bounded and reports the ceiling it enforced, and each
  carries `WakeReason` through — a server that died under a wait is reported as
  `SERVER_GONE` rather than as success, which is the one thing tmux's own
  `wait-for` cannot tell a caller.
- **`tmux_capture_since` returns only what is new.** It hands back an opaque
  cursor; passing it back costs the lines a pane has added rather than the screen
  again. The same read that fetches new output also proves it follows on from the
  last, and says `continuous: false` when a clear or a rolled-over history means
  it does not. That proof needs the capture and the pane's position to come from
  one tmux invocation, and it anchors only to lines the terminal's cursor has
  moved past — a pane that merely scrolled, or a line still being drawn, is not a
  discontinuity.
- **A safety ceiling that removes tools rather than refusing them.**
  `--safety readonly|mutating|destructive`, or `LIBTMUX_SAFETY`. A tool above the
  ceiling is never listed, so a model is not offered something it will only be
  refused, and the server's instructions say plainly what is missing. MCP's own
  `readOnlyHint` and `destructiveHint` are derived from the same tier.
- **The pane the conversation runs through is known, and protected.** `tmux_whoami`
  names it, resolving `TMUX_PANE` against the server's own socket path before
  believing it. `tmux_kill` refuses that pane, and the window and session holding
  it, unless `confirm_self` is passed.
- **Resources, prompts, live completion and server instructions.** `tmux://`
  resources expose the same state for a client to hold without spending a tool
  call; five prompts carry the recipes that take several tools in an order that
  matters; and `completion/complete` is answered from tmux, so a client asking
  what could go in `{pane_id}` gets the ids that exist right now.
- **`--watch` turns tmux's own change detection into MCP notifications.** A
  control client and `refresh-client -B` let tmux compare formats on its own timer
  and report only differences, so a subscribed client spends nothing while a
  server is idle. The client this attaches is hidden from `tmux_list_clients`, so
  watching cannot be mistaken for a person watching.
- **`ControlClient` surfaces what tmux volunteers.** `onEvent` publishes every
  notification a control client is sent, and `watch`/`unwatch` register a format
  for tmux to report when its value changes.
- **`tmux_apply_workspace` builds a whole session from one document**, in the
  shape tmuxp uses — one call instead of a dozen, and a layout tmux would refuse
  is refused before anything is half-built.

### Changed

- **Every read is bounded and says what it dropped.** A capture keeps the newest
  lines within a line budget and a character budget, because a pane showing
  minified output is one line of half a megabyte and a line budget alone lets it
  through. An answer silently shortened reads as a complete one.
- **Answers are objects, in snake_case, with nulls omitted.** Named fields rather
  than a bare array, so a model does not count positions to find out how many
  panes it got, and the same convention as the arguments it sent. Sent as
  `structuredContent` and as text.
- **Failures name the recovery.** `no pane %9 on this server; call tmux_list_panes
  for the 3 that exist`, rather than a message a model can only repeat.

### Removed

- **`TmuxTools`, `PaneSummary` and `SessionSummary`.** The tool surface is
  declared in one place now, with each tool's arguments, risk and behaviour stated
  together so they cannot drift apart.

## 0.0.1-alpha.3 — 2026-08-16

### Fixed

- **The fixture's sweep counted servers it had only asked to stop.**
  `TmuxExtension` ended an abandoned server with `destroy`, which sends SIGTERM
  and returns; tmux answers that by destroying every session and reaping each
  pane's children first, so the process outlived the signal by up to 244ms under
  load. The sweep now waits for each exit and counts what ended, which is what it
  always claimed to return. The tmux matrix failed on that margin on its slowest
  lane.

### Documented

- **A `:` or `.` in a session or window name does three different things across
  the supported range.** 3.2a through 3.6 rewrite each one to `_` in a session
  name, 3.7 refuses the name outright, and 3.7a onwards keeps it — where it can
  no longer address the object, because a target splits on both. A window name is
  never rewritten, only kept or refused. `Server.newSession` and `Window.rename`
  now say so, and the behaviour is asserted on every lane.

## 0.0.1-alpha.2 — 2026-08-16

### Added

- **`Pane.retitle` gives a pane the title it reports.** `Pane.title()` could be
  read and not set, so setting one meant reaching past the API for
  `select-pane -T`. It returns a handle carrying the new value, the way
  `Window.rename` and `Session.rename` already do. A program running in the pane
  can still set its own title through an escape sequence, so what comes back says
  what the title is now and not what it will stay.

### Fixed

- **The published POM sent readers to the Python library's documentation.** Its
  `url` was `https://libtmux.git-pull.com/`, so the homepage on this artifact's
  Central page belonged to a sibling project. It now names this repository, which
  is the reasoning the `scm` block already carried and this element had missed.
  `0.0.1-alpha.1` carries the wrong value permanently — a released POM cannot be
  changed.

## 0.0.1-alpha.1 — 2026-08-16

First release. Published to Maven Central under `io.github.libtmux`, signed with
key `D6B3443B2E8F467A7CEC14BF3FACCB0FE2F4C97B`.

### Added

- **Documentation shows what every call returns, and the value is asserted.** A
  line ending in an arrow — `session.name(); // -> demo` — becomes a comparison
  against what the expression produces, so a README cannot claim a result the
  library does not give. It caught three claims that read perfectly and were
  false on the day it was added.
- **A README in every published package**, each with an install snippet, a
  task-oriented tour with executed examples, and what it deliberately does not do.
- **`libtmux-kotlin` gained the `filter` overload Kotlin actually needs.** Kotlin's
  own `filter` takes a function rather than a `Predicate`, so passing a
  `FilterExpr` to it did not compile — the documentation had claimed it did.
- **Javadoc runs in `check`**, so the standard doclet and the jar the Portal
  requires are exercised on every push rather than for the first time after a tag
  is pushed.
- **Kotlin shows results too.** The generator translates an arrow into an
  assertion, the same rule the Java fences follow, so both languages are held to
  one standard. 103 shown values are asserted across the READMEs and guides, and
  no document with executable code is left without one.
- **Kotlin snippets are executed too.** `libtmux-kotlin` generates a test from
  every Kotlin fence in the documentation, so the Kotlin compiler and a real tmux
  check them the way `docs-tests` checks the Java ones. 66 shown values across the
  READMEs and guides are now asserted.
- **`DocumentationFactsTest`** holds the claims that are prose rather than code:
  every install snippet names the version this build publishes, the platform's
  README lists exactly what it constrains, and every published module has a README
  that names its coordinate.
- `RELEASING.md`, covering namespace verification, the signing key, and why the
  publishing plugin is not the one most tutorials name.
- `examples/`, whole runnable programs whose own suite runs each against real
  tmux, and `scripts/` for building the tmux matrix and reaping abandoned
  servers by hand.
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
  declares a Maven publication, and the build now fails when that set stops
  matching `libtmux-bom`, rather than shipping an artifact the platform does not
  manage. It asks about the publication rather than the plugin because those came
  apart once already: `libtmux-kotlin` applied the publishing convention, which
  configures publications rather than creating one, and released no jar while
  looking published to the build.
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

- **The group is `io.github.libtmux`**, verified on the Central Portal, and the
  version is `0.0.1-alpha.1`. `alpha` is the lowest qualifier Maven's comparator
  recognises, so nothing published later can sort beneath it.
- The MCP server reports the version from its jar manifest instead of a literal,
  which had already drifted to `0.1.0`.
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
