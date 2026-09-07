# Changelog

Notable changes, newest first. Versions follow [semantic versioning][semver].

**This project is alpha.** Releases carry an `-alpha` prerelease tag. The API is
not settled, and any release may change or remove exported identifiers without a
deprecation period. Only the newest release is supported, and there are no
backports. Pin an exact version rather than a range. Not recommended for
production.

[semver]: https://semver.org/spec/v2.0.0.html

## Unreleased

### Development

- **Every CI job carries a timeout.** The `tmux 3.3a` lane ran for six hours
  against a median under two minutes before GitHub's ceiling stopped it. Each
  job now fails in minutes instead. (#14)

## 0.0.1-alpha.10 — 2026-09-06

### Fixed

- **Startup refuses a tmux socket path that names no file.** tmux 3.4 and 3.5
  escape a non-printable byte in the socket path when they store it at server
  start, so a format reports a rendering with no control character left to
  refuse, naming nothing. That path is frozen as the `-S` argument for every
  pane command frame, so `run_shell_command` framed its completion against a
  socket that reached no server and the reported attach command was wrong.
  Those two releases now fail closed at startup like every other supported
  release. (#12)

## 0.0.1-alpha.9 — 2026-09-06

### Added

- **`tools/mcp-swap` configures all eight supported agent clients.** It replaces
  the retired `scripts/mcp_swap.py`, builds through
  `./gradlew :tools:mcp-swap:installDist`, and decodes JSON, JSONC, and TOML
  with strict UTF-8. OpenCode edits preserve JSONC comments and trailing commas,
  Pi reports its adapter prerequisite, and `antigravity` selects canonical
  `agy`. Multi-client use and revert preflight and stage one transaction,
  preserve config symlinks, reverse proven writes on failure, and keep
  `--dry-run` fully observational. Persistent, versioned recovery records bind
  each backup to the exact swapped config, path topology, and server route;
  drift fails closed without deleting recovery.
- **`NamedServerFixture` safely owns explicitly named test servers.** It binds
  teardown to the reported process, socket path, and inode, then fails closed if
  any of that identity changes before cleanup.

### Changed

- **`libtmux-mcp` now exposes a fixed 45-tool capability surface.** One native
  registry drives tool registration, schemas, trust metadata, selection, and
  the static `tmux://capabilities` resource. Unordered toolsets and named
  include/exclude lists replace safety tiers; the retired `LIBTMUX_SAFETY`
  variable and `--safety` option now fail with migration guidance. The retired
  `LIBTMUX_WATCH` variable and `--watch` option also fail; use bounded wait and
  capture tools instead of dynamic resource notifications. The server defaults
  to the dedicated `libtmux-mcp` socket, supports separate socket-name and
  absolute socket-path selectors, and enables teardown by default only for a
  newly created minimal daemon.
- **Copy-mode entry and exit remain library-only.** The MCP surface reads pane
  text through `capture_pane` history, `snapshot_pane`, `search_panes`, or
  `capture_since` without taking ownership of an attached client's modal
  interface. Java callers retain `Pane.copyMode` and `Pane.exitMode`.
- **The MCP guide maps every earlier public tool, resource URI, prompt workflow,
  and completion path.** Each retired name now points to its current typed
  route, composed workflow, or explicit no-replacement boundary.
- **MCP searches and read batches now have fixed work ceilings.** Search stops
  after 200 panes, 20,000 lines, 1,000,000 bytes of matching input, or five
  seconds. Read batches validate each nested call and cap the complete JSON-RPC
  response, including line framing, at 1,000,000 bytes without dropping an
  executed row. Request IDs over 512 KiB now fail before dispatch rather than
  consuming that response budget.
- **`capture_since` and `call_read_tools_batch` now advertise observe-only tmux
  effects.** Cursor capture and every batch-eligible inspect operation leave
  tmux state unchanged.

### Fixed

- **The MCP server no longer holds its send lock across a completion callback.**
  The pinned SDK resolves a stdio send on the subscribing thread, so a queued
  send ran one stack frame deeper than the last, and a caller's callback blocked
  every other thread's send. Sends are now promoted iteratively, with the
  transport lock released before the delegate writes and before a caller runs.

- **`Pane.sendKeys` preserves option-shaped input.** It ends tmux option parsing
  before caller keys, so values such as `-X`, `-R`, and `-N` reach pane programs
  through the core API and MCP single or batch routes.
- **`Pane.breakOut` preserves literal `#` in requested window names.** The
  `break-pane -n` path receives the raw name; only the tmux 3.7 rename fallback
  applies tmux format literalization.
- **MCP capability rows disclose both output risk dimensions.** Pane text,
  environment and configured-command values, and names, titles, paths, or
  current commands now advertise both secret and untrusted-content risk;
  strictly structural results remain false for both.
- **MCP pane input now refuses effective recipients in a human-owned mode.**
  `send_keys` and each `send_keys_batch` operation resolve pane-level
  `synchronize-panes` overrides before dispatch; `paste_text` remains
  target-only, dead configured recipients fail closed, and
  `run_shell_command` checks a singular cohort before setup and again before
  input because its completion, output, and status are singular.
- **`run_shell_command` no longer relies on mutable pane-shell framing state.**
  Completion markers and signalling run in an isolated outer subshell through
  an absolute selected tmux executable and the server's resolved `-S` socket,
  so ordinary output-command aliases/functions, pane `PATH`/socket variables,
  inherited `errexit`, and a readonly nonce name cannot lose completion or
  close the pane; the frame also leaves no status variable behind. This assumes
  the parent shell has not replaced the exact client word or
  `trap`/`eval`/`exit` with functions, and marker commands honor trusted server
  hooks.

### Removed

- **MCP prompts, completions, watches, dynamic resources, per-call server
  discovery, and workspace tools are removed.** Use the fixed tool surface and
  its static `tmux://capabilities` resource.

## 0.0.1-alpha.8 — 2026-08-30

### Added

- **`Pane.findWindow` searches by name, title, or content, with
  case-insensitive and regular-expression matching.** Build a `FindSpec` or
  configure one inline. (#6)
- **`Batch.length()` reports the exact UTF-8 byte length of the encoded
  command.** Use it to dispatch before tmux's command-size limit is reached.
  (#6)
- **`Pane.paste(String)` sends literal text without leaving a server buffer.**
  Text no longer shares tmux's command-size limit; this API requires tmux 3.4.
  (#6)

### Changed

- **`Pane.findWindowByName` and `Pane.findWindowByContent` are removed.** Use
  `findWindow` with `inName()` or `inContent()`. (#6)
- **`Server.waitFor`, `waitForWithSignalCapacity`, `signal`, and `drain` move
  to `Server.channel(name)`.** Use `await`, `awaitReservingCapacity`,
  `signal`, and `drain` on the returned `Channel`. (#6)
- **`Pane.mode()` returns `PaneMode` rather than text.** Compare against enum
  values such as `PaneMode.TREE`; `modeOrNull()` changes with it. (#6)
- **The supported tmux range now includes 3.7c.** The compatibility matrix runs
  that lane. (#6)
- **`Server.snapshot()` captures the hierarchy in one fenced command group.**
  Watchers start fewer tmux processes, and a replacement server is rejected
  before its rows are read. (#6)
- **`CommandRequest` carries command groups and optional input rather than one
  flat argv.** Use `CommandRequest.of` for one command and `commands()` where
  `argv()` was read; `ControlClient.isCommandGroup` is removed. (#6)
- **`Pane.paste(String bufferName)` is now `Pane.pasteBuffer(String name)`.**
  `Pane.paste(String)` now means literal text. (#6)
- **`ControlClient.onOutput` and `onEvent` are replaced by bounded pull
  subscriptions.** Use `subscribeOutput` or `subscribeEvents`, close the
  returned `EventSubscription`, and inspect `droppedCount()`. (#6)
- **Filter expressions are immutable, model-bound wire values.** Build fields
  and relations through `Fields` or generated handles, and pass the matching
  `FilterModel` to `FilterJson.write*`; `EntityMetamodel`, `FieldProvenance`,
  and `FieldRef.name()` are removed. (#6)
- **`ProcessTransport` now bounds concurrency, input writes, output, deadlines,
  and cleanup.** Configure its limits through the constructors; timeouts
  surface as `TmuxTimeoutException` with dispatch certainty. A deadline or
  cancellation during input terminates the child instead of blocking. (#6)
- **`Buffers.delete` now requires tmux 3.4 and reports a missing name.** tmux
  3.2a and 3.3a can delete the top buffer when the named buffer is absent, so
  the library refuses that unsafe operation. (#6)

### Fixed

- **Destructive MCP tools fail closed when caller-pane identity cannot be
  proven.** Uncertain socket, server, or pane identity now requires explicit
  self-confirmation instead of bypassing the guard. (#6)
- **MCP pane cursors are authenticated and bound to daemon and pane identity.**
  Reads preserve continuity across bounded history compaction when it can be
  proven, and pane capture uses identity-fenced batches with strict outcome
  checks. (#6)
- **`RowFormat` uses an explicit record terminator.** Multiline final fields
  and single-field rows no longer depend on physical line boundaries. (#6)
- **`tmux_run` requires a POSIX shell and uses a 128-bit completion marker.**
  It refuses another foreground program instead of sending shell framing that
  program cannot interpret. (#6)
- **Hierarchy listings preserve values containing newlines.** A pane working
  directory containing a newline no longer empties session, window, and pane
  listings. (#6)
- **`Options.all`, `Options.effective`, and `Options.get` return complete
  stored values.** Escape-sensitive and multiline strings are no longer
  altered or truncated. (#6)
- **`Server.expand`, `Session.expand`, `Window.expand`, and `Pane.expand`
  preserve multiline results.** They no longer return only the first line.
  (#6)
- **Names, options, sent keys, shell commands, and batch operations preserve a
  trailing semicolon.** The semicolon remains data rather than ending the tmux
  command. (#6)
- **`Pane.sendLine`, command-chain input, and `tmux_run` deliver text plus
  Enter as one literal operation.** Option-shaped input remains data, and
  concurrent calls cannot interleave their input. (#6)
- **`tmux_paste_text` no longer leaves pasted text on the server when a client
  disconnects during the call.** (#6)
- **`tmux_run` keeps caller shell syntax isolated from completion framing, uses
  the server's resolved tmux binary, and keeps completion state off pane
  options.** A command accepted before an indeterminate send failure still
  executes. (#6)
- **Serialized MCP sends obey their queue and byte bounds across cancellation
  and shutdown.** A send cancelled after promotion is skipped before delegate
  delivery, later sends keep order, and close settles every admitted caller.
  (#6)
- **`libtmux-mcp` publishes the dependencies its API exposes.** Consumers now
  receive `mcp-core`; the artifact no longer selects an SLF4J provider or
  exports `libtmux-jackson`. (#6)
- **Handles refuse a replacement tmux server that reused an identifier.**
  Linked-window operations also retain the exact session and index they came
  from. (#6)
- **Workspace input is validated before session creation.** Unsupported
  layouts, unsafe names, malformed topology, and uncertain creation replies
  leave tmux untouched or roll back the exact staging session. (#6)
- **MCP watching stays consistent across concurrent output, new sessions,
  dropped events, outages, and server restarts.** Notifications are serialized
  and bounded, and watcher clients remain hidden from listings. (#6)
- **The MCP launcher exits when its protocol session ends.** Oversized or
  malformed input and output failures no longer leave the process waiting on
  stdin. (#6)
- **MCP tools now advertise non-additive effects as destructive.** Clients can
  request confirmation for commands, input, and settings even when the safety
  ceiling permits those tools. (#6)
- **MCP rename and kill tools resolve destructive targets unambiguously.**
  Session operations use stable identifiers; only arguments explicitly naming
  a session by name use names. (#6)
- **Snapshot-backed accessors reject a closed `Server`.** They no longer turn
  use after close into an empty hierarchy. (#6)
- **A live server with no sessions captures as an empty snapshot.** Child
  listings are not attempted when tmux has no current target. (#6)
- **`TmuxExtension` proves abandoned servers exited before deleting their
  directories.** Successful recovery also removes the abandoned directory.
  (#6)

### Removed

- **`ExecutionMode`, `ControlTransport`, `VirtualThreadTransport`,
  `LIBTMUX_MODE`, and their benchmark surface are removed.** `Server` uses
  process execution; use `ControlClient` for event streams and batches or
  command chains to reduce round trips. (#6)

## 0.0.1-alpha.7 — 2026-08-22

### Documented

- **`CONTRIBUTING.md` has moved to `.github/CONTRIBUTING.md`.** The policy an
  agent or contributor reads is now split three ways: `AGENTS.md` routes and
  carries only what applies to every change, `.github/WRITING.md` covers how
  this project writes, and `.github/CONTRIBUTING.md` covers how it works. A
  link to the old path needs updating; GitHub finds the new one on its own.

## 0.0.1-alpha.6 — 2026-08-16

### Fixed

- **`tmux_whoami` failed on a socket with no server behind it.** It is the tool
  the instructions tell a model to call first, and it asked tmux for its version —
  which needs a running server — so the first call on an unstarted socket answered
  with a raw `error connecting to …`. It now says there is no server and points at
  `tmux_list_servers`.
- **An empty listing could not be told from an absent server.** `tmux_list_panes`
  on a socket with nothing behind it answered `count: 0`, exactly as a running
  server with no panes does. Both now carry a note saying which, asked only on the
  empty answer so a listing that found something costs no extra tmux command.
- **A single value where a list was wanted was refused before the tool saw it.**
  The server validates arguments against each tool's schema first, so `keys: "q"`
  was rejected even though the reader behind it coped — and the test that covered
  it called the tool in process, where no validation happens. The schema now
  accepts one value or a list, and the case is tested over the wire.

### Changed

- **Refusing to end the caller's own pane leads with what can be ended.** The
  earlier wording offered `confirm_self=true` as the next step; a model told to
  tidy up read that as how to finish the job. It now names the other panes first
  and says what the override costs — measured against a real agent, whose account
  of what it had done went from wrong to accurate.

### Added

- **`FilterModel.fieldNames` and `relationNames` say what a document may name.**
  The useful thing to tell a caller whose field was not recognised is which ones
  exist, and nothing outside the class could find that out. `libtmux-mcp` now puts
  the pane model's fields in `tmux_list_panes`'s own description and repeats them
  when a filter will not read — measured against a real agent, which guessed a
  plain field map and spent a call discovering the shape.

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
