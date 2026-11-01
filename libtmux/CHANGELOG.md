# Changelog

Notable changes to `libtmux`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

The modules are released together at one version, so every release since this
module first shipped has a section here. Changes that span modules, such as
documentation, CI and release tooling, are in the [main
changelog](../CHANGELOG.md).

## Unreleased

## 0.1.2 — 2026-11-01

No entries for `libtmux`.

## 0.1.1 — 2026-11-01

### Changed

- **JSON layouts are floored at tmux 3.8.** tmux 3.8 applies a JSON layout; a
  server below 3.8 gets an `UnsupportedFeatureException` naming 3.8 before
  `select-layout` is sent. A version probe may run first. Installed releases
  behave as before. (#106)

## 0.1.0 — 2026-10-25

No entries for `libtmux`.

## 0.0.1-alpha.19 — 2026-10-24

### Added

- **Capabilities are recorded in one registry and a `capabilities.json`
  manifest.** The registry names each optional tmux feature and release defect
  the library gates, with the release it needs and how it refuses or repairs.
  The gates read it, and the manifest at the repository root is written from it
  for the capability pages on libtmux.org. (#72)

- **`TextOutcome.PANE_GONE`, `TextWait` and `Pane.watchText`.** `watchText`
  waits like `Pane.awaitText` and also returns the pane's output at its last
  read. (#72)

### Changed

- **Managed control now requires tmux 3.7+ and a pinned socket.** Older
  daemons are refused before control starts; ordinary operations keep the 3.3a
  minimum. See [migration notes](../MIGRATION.md#optional-tmux-requirements). (#72)

- **Named buffer deletion, rename and managed paste require tmux 3.4+.** On
  3.3a a missing name can select an unrelated buffer. (#72)

- **Invalid literal layouts are refused on tmux 3.3a.** The refusal is an
  `UnsupportedFeatureException` before any command of the batch runs. (#72)

- **Literal `split-window -p` is refused on exact tmux 3.4.** Typed percentage
  splits keep working. (#72)

- **`refresh-client -l` is refused below tmux 3.6a.** On 3.3a it ends the server
  when a client attached from an `xterm` or `tmux-256color` terminal receives
  the query, and on 3.4 to 3.6 it ends the server when the client leaves before
  its terminal answers. (#72)

- **Raw and unnamed `break-pane` is refused on exact tmux 3.7.** Named breaks
  retain the requested name, and single-pane breaks also work without native
  `-P` output. (#72)

- **Exact tmux 3.7 refuses `.` and `:` in session and window names before
  dispatch.** A batch or workspace no longer runs the commands ahead of the
  one tmux would reject. (#72)

- **`Pane.awaitText` answers `TextOutcome.PANE_GONE` for a pane killed
  mid-wait** instead of throwing `CommandRejectedException`. (#72)

## 0.0.1-alpha.18 — 2026-10-19

### Added

- **`TmuxVersion.MINIMUM` and `UnsupportedFeatureException.belowFloor()`.**
  `MINIMUM` is the oldest supported tmux, 3.3a. `belowFloor()` is true when the
  exception reports a tmux older than that and false when one optional feature
  is missing from a supported tmux, so a handler that skips optional features
  can still surface a floor refusal. (#69)

- **`CommandRequest` can discard standard output.** `discardStdout` sends it to
  the null device for a caller that reads only the exit status and standard
  error; the version probe uses it, because tmux 3.0 and 3.1 daemons hold the
  client's standard output open after it exits. The four-argument constructor
  is unchanged. (#69)

### Changed

- **`TmuxVersion` orders release candidates below their release and parses
  strictly.** `3.8-rc < 3.8`, `compareTo` agrees with `equals`, and text such
  as `3.3a garbage`, `3.3a-master`, `next-3.9a` or `3.8-rc0` is rejected.
  `atLeast` follows the same order, so a candidate or `next-` build no longer
  meets a gate on the release it names. (#69)

- **JSON layouts are gated on tmux 3.8-rc2.** The release candidates already
  write them, so the gate names the earliest build measured to; `3.8-rc` and
  `next-3.8` are still refused. (#69)

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux`.

## 0.0.1-alpha.16 — 2026-09-26

### Added

- **`Layout.byTmuxName` resolves a built-in layout by its exact tmux name.**
  (#16)

### Changed

- **`CommandResult.stdout()` keeps carriage returns.** It splits at LF alone,
  so `Buffers.show`, pane captures and option reads return `\r` and `\r\n` as
  tmux sent them. Strip `\r` yourself where a line ending must be bare.
  `stderr()` still turns CRLF and CR into LF. (#16)

### Fixed

- **A relative start directory resolves against this process on every
  release.** `WindowSpec.Builder.in`, `SplitSpec.Builder.in`,
  `SessionSpec.Builder.in` and `Pane.respawnIn` send it absolute. tmux 3.2a
  resolved a relative `-c` against the server's working directory, falling
  back to home, so a pane could start somewhere the caller never named.
  `WindowSpec.Builder.in` also works on 3.2a now, where it threw
  `UnsupportedFeatureException`. (#16, #23)

- **Option values read from tmux 3.4 and 3.5 keep a carriage return apart from
  a literal `\r`.** Both releases print the two alike under `-v`, so
  `Options.get`, `Options.all` and `Options.effective` read tmux's quoted
  listing on them instead. (#16)

- **`Options.all` and `Options.effective` keep a user option's trailing `*`.**
  Only the `*` tmux appends to an inherited built-in is removed. (#16)

- **Classic layout validation accepts a layout longer than 8191 characters**,
  still checking its checksum, geometry, depth and pane count. (#16)

## 0.0.1-alpha.15 — 2026-09-26

### Added

- **`ServerMirror` keeps a live copy of a server.** It listens through a
  control client attached to one session and takes a fresh snapshot whenever
  tmux announces a change, publishing each changed capture as a numbered
  `View`. `awaitNewer` blocks for the next one and `onNewer` arms a one-shot
  callback. A lost control client is reattached through the same session;
  once that session has gone, the mirror ends with `TargetGoneException`.
  `open(anchor, refreshEvery)` also rebuilds on a timer, for changes tmux
  does not announce to this client. (#23)

- **`Server.session`, `window` and `pane` take a filter expression.** Each
  returns the one match or empty, and throws
  `CardinalityException.MultipleMatches` with the exact count when several
  match. (#23)

- **`ServerConfig.Builder.maxConcurrentCommands(int)` and
  `Server.admissionBound()`.** How many tmux commands `Server.open` lets run
  at once is now a choice, and readable, so a coroutine dispatcher or effect
  pool can be sized to it. (#23)

- **`EventSubscription.poll()` and `onReady(Runnable)` read without blocking
  a thread.** `poll()` answers at once; `onReady` arms a one-shot wakeup for
  when a step arrives or the subscription ends. (#23)

- **`EventSubscription.stream()` and `publisher()`.** `stream()` reads a
  subscription as a `Stream` on the consuming thread. `publisher()` and
  `publisher(Executor)` expose a `java.util.concurrent.Flow.Publisher` for
  Reactor, RxJava or Mutiny through `FlowAdapters`, with demand-driven
  delivery; it passes the Reactive Streams TCK, and an executor that refuses
  work ends the subscriber with `onError`. (#23)

- **`DispatchException.safeToRetry()` says whether resending is safe.** A
  failure is safe to resend when the request never reached tmux, or when
  every command in it only reads. `CommandRequest.idempotence()` and
  `DispatchOutcome.canRetryVerbatim` answer the same question for a caller
  holding its own outcome. Nothing retries on its own. (#23)

- **`ServerConfig.observer` reports each command after it ends.** A report
  names the verbs, the dispatch outcome, the exit status, how many lines came
  back, and how long the call waited and ran. It omits the arguments and
  standard output, and keeps at most 240 characters of standard error. An
  observer that throws is logged with its stack trace and does not change the
  command's result. (#23)

- **`PaneInput.hold` keeps one writer on a pane.** `sendKeys`,
  `sendLiteral`, `paste`, `pasteBuffer` and `run` take that hold for the
  call, and another thread is refused until it closes.
  `holdInterruptible` lets a second thread `enterInterrupt` to stop a long
  run. `held()` and `heldSince` list what is held, and since when. (#23)

- **A `TmuxTransport` says how it starts a control client.**
  `controlCarrier()` returns the transport's `ControlCarrier`, or empty; with
  empty, a control client is refused rather than started on the local
  machine. `observe` hands the transport the configured observer, and
  `admissionBound()` reports how many requests it runs at once. (#23)

- **Every public tmux operation is classified.** `@Operation` in
  `io.github.libtmux.catalog` marks each method `CAPTURED`, `READ`,
  `MUTATION`, `WAIT`, `STREAM` or `LIFECYCLE`, and `@Advanced` marks the
  orchestration methods a language facade does not mirror. The jar carries
  the catalog with each operation's Javadoc at
  `META-INF/io.github.libtmux/operation-catalog.json`.
  [`docs/reference/operations.md`](../docs/reference/operations.md) is generated
  from it. (#23)

- **More pane, window and session fields are queryable.** `Pane_.atTop()`,
  `atBottom()`, `atLeft()` and `atRight()`, `Window_.width()`, `height()` and
  `paneCount()`, and `Session_.windowCount()`. The field list ships in the
  jar at `META-INF/io.github.libtmux/field-catalog.tsv`. (#23)

- **`Delivery.kept` fails a read on a gap.** A reader that needs every event
  gets the value, or `lost N` when the buffer discarded some. (#23)

- **`info()` is the captured moment.** `Session`, `Window` and `Pane`
  return the snapshot record their accessors read. (#23)

### Changed

- **A handle used after its `Server` closed throws `ServerClosedException`.**
  It sits outside the sealed tree, and `outcome()` says whether a command the
  close interrupted may have reached tmux. (#23)

- **`Notification` has four more cases.** `%pause`, `%continue`, `%message`
  and `%config-error` arrive as `Notification.Pause`, `Continue`, `Message`
  and `ConfigError` instead of `Unknown`, so an exhaustive `switch` needs
  them. See [migration
  guidance](../MIGRATION.md#notification-has-four-more-cases). (#23)

- **An `EventSubscription` has one reader.** A read that overlaps another, or
  any read after `stream()` or `publisher()` took it, throws
  `IllegalStateException`. Subscribe again for a second reader. See
  [migration guidance](../MIGRATION.md#an-eventsubscription-has-one-reader).
  (#23)

- **`EventSubscription.next` returns a `Delivery`.** A full buffer's next
  read is a `Delivery.Gap` naming how many events were discarded; `cause()`
  says why the client ended the subscription. See [migration
  guidance](../MIGRATION.md#eventsubscriptionnext-returns-a-gap-before-the-events-that-remain).
  (#23)

- **Filtered reads are applied by tmux.** `sessions`, `windows` and `panes`
  with a `FilterExpr`, and the name, id and expression lookups, send what tmux
  can evaluate as a `-f` format and read only the matching sessions, in two
  tmux commands whether or not anything matches. Relations and `matches`,
  whose regular expressions tmux reads in another dialect, stay local; what
  comes back is still tested against the expression. (#23)

- **`Client.refresh()` returns the client, and throws `TargetGoneException`
  once it has detached,** as every other handle does. See [migration
  guidance](../MIGRATION.md#clientrefresh-returns-the-client-or-throws). (#23)

- **A handle is refused by a tmux restarted on its pid.** A capture records
  when its server started, and every handle command, capture fence and
  control attach compares it with the pid. `ServerSnapshot.serverStartTime()`
  reports it. (#23)

- **`Server.control` attaches to the tmux a capture named.** A socket reused
  by a new server is refused. `ControlClient.attach(config, session)` is now
  `attachUnfenced`. See [migration
  guidance](../MIGRATION.md#an-attachment-that-skips-the-incarnation-check-says-so).
  (#23)

- **Server-wide scopes moved off `Server`.** The command catalog is
  `server.commands().list()`, shell commands are `server.shell()`, the
  message log is `server.messageLog().lines()`, and prompt history is
  `server.prompt()`. See [migration
  guidance](../MIGRATION.md#prompt-history-is-serverprompt). (#23)

- **`killServer` returns after the daemon exits,** so a server started
  straight afterwards cannot reach the dying one. (#23)

### Fixed

- **On tmux 3.2a, a session under a missing socket directory names it.**
  tmux 3.2a exits 0 and prints nothing when it cannot create its socket, so
  `newSession` said the binary might not be tmux. (#23)

- **Values read from tmux 3.4 keep their `$`.** tmux 3.4 printed `$HOME` as
  `\$HOME` in names, titles, options and formats. (#23)

- **A name lookup finds the name tmux stored.** `session(name)`,
  `hasSession` and `killSession` find a name holding `.`, `:` or a
  backslash, which tmux stores differently. (#23)

- **Interrupting one `ControlClient.send` no longer ends the client for
  every other caller.** The interrupted caller fails alone, with outcome
  `UNKNOWN`. (#23)

- **A control reply is no longer another command's.** A request waiting
  behind an `if-shell` could receive the branch's output as its reply. (#23)

- **Pushed pane output keeps a character tmux cut in two,** and
  `PaneOutput.bytes()` is exactly what a push carried. (#23)

- **Output arrives with tmux's flow control on.** After `refresh-client -f
  pause-after=N`, none of the `%extended-output` tmux sends reached
  `subscribeOutput`. (#23)

- **A subscription the control client ended keeps what it had delivered.**
  Buffered events are read before `next()` returns empty. (#23)

- **A notification keeps a name that contains `" : "`.** A window renamed
  `left : right` was announced as `left`. (#23)

- **A value that ends in a line break keeps it.** `expand` and
  `Options.get` read `"a\n"` as `"a"`. (#23)

### Removed

- **`Server.setMouseEnabled`.** Write `globalOptions().set("mouse", "on")`.
  See [migration guidance](../MIGRATION.md#serversetmouseenabled-is-gone). (#23)

## 0.0.1-alpha.14 — 2026-09-20

No entries for `libtmux`.

## 0.0.1-alpha.13 — 2026-09-20

### Fixed

- **`Options.effective` includes inherited built-in option values.** (#19)

## 0.0.1-alpha.12 — 2026-09-19

### Added

- **`Pane.run` runs a command to completion and reports its output and exit
  status.** (#17)

- **`Server.within` bounds commands made through the returned handle.** (#17)

- **Pane waits accept a polling interval** for callers that need less frequent
  reads. (#17)

- **Server and session environments can be read and changed.**
  `Environment.effective` reports what new processes inherit. (#17)

- **`OptionKey` reads and writes options as their declared types.** (#17)

- **Hook commands accept argument lists** through `Hooks.set` and
  `Hooks.append`. (#17)

- **`Server.paneFields` reads selected fields across all panes.** (#17)

- **Pane filters can match title, path, size, and position.** (#17)

- **The core artifact declares the Java module `io.github.libtmux`.** Internal
  packages are encapsulated on the module path. (#17)

- **`Pane.dead` reports whether a pane process has exited.** (#17)

- **`Pane.position` reports a pane's terminal coordinates.** (#17)

- **`Window.previousLayout` cycles backward through preset layouts.** (#17)

- **Command names can be logged through `System.Logger` at `DEBUG`** without
  exposing arguments. (#17)

### Changed

- **Failed reads throw instead of returning false or empty results.** An absent
  daemon raises `ServerNotRunningException`; see [migration
  guidance](../MIGRATION.md#failed-live-reads). (#17)

- **`server.keys()` replaces `bindKey`, `unbindKey`, and `listKeys`.** See
  [migration guidance](../MIGRATION.md#key-bindings-are-a-view-serverkeys). (#17)

- **`Window.layout` distinguishes classic and JSON layouts.** See [migration
  guidance](../MIGRATION.md#windowlayout-answers-a-windowlayout). (#17)

- **Control notifications expose typed events and identifiers.** See the
  [migration guide](../MIGRATION.md). (#17)

- **`Pane.pid` reports an absent process explicitly.** See [migration
  guidance](../MIGRATION.md#panepid-returns-optionallong). (#17)

- **`raiseIfDead` becomes `requireAlive`; renamed exceptions end in
  `Exception`.** See [migration
  guidance](../MIGRATION.md#exception-and-guard-names). (#17)

- **Interactive pane chooser methods are removed.** Use `Server.cmd` for
  attached-terminal commands; see [migration
  guidance](../MIGRATION.md#the-interactive-chooser-methods-on-pane-are-removed).
  (#17)

- **The keyword filter parser is removed.** Use typed filters or `FilterJson`;
  see [migration guidance](../MIGRATION.md#legacyfilters-is-removed). (#17)

- **Snapshot construction requires server identity.** See [migration
  guidance](../MIGRATION.md#serversnapshotof-needs-the-servers-identity). (#17)

- **Error Prone flags discarded replacement handles.** Retain rename, retitle,
  and refresh results; see [migration
  guidance](../MIGRATION.md#discarded-replacement-handles). (#17)

- **Control clients request JSON layout notifications when tmux supports them.**
  See [migration
  guidance](../MIGRATION.md#controlclientattach-requests-json-layouts-on-connect).
  (#17)

### Fixed

- **Text waits discount recognized echoes of library input.** Exact output
  repeats and partial redraws remain ambiguous; wait for a cold shell's prompt
  before typing. (#17)

- **Pane waits find text wrapped across terminal rows.** (#17)

- **Non-ASCII arguments and output survive non-UTF-8 JVM locales.** See
  [migration guidance](../MIGRATION.md#non-ascii-text-reaches-tmux-on-any-locale).
  (#17)

- **Caller text beginning with a dash stays positional.** This includes typing,
  shell commands, creation programs, popups, and pipes; raw `cmd` arguments
  remain caller-controlled. (#17)

- **Forgotten server and control-client handles no longer keep the JVM running
  indefinitely.** (#17)

- **Cancelling a channel wait consistently throws `InterruptedException`.** See
  [migration
  guidance](../MIGRATION.md#a-cancelled-channel-wait-throws-interruptedexception).
  (#17)

- **Development and release-candidate tmux version strings can be read.** (#17)

- **tmux 3.3 supports prompt history, window start directories, and detached
  session sizes** without requiring the later 3.3a patch. (#17)

- **Sized sessions can be created before the tmux server is running.** (#17)

- **Layout selection accepts JSON layouts and unique preset prefixes when
  supported by tmux.** Unsupported layouts are refused before dispatch. (#17)

- **Failed commands report tmux diagnostics or the process-launch reason.**
  (#17)

- **Session-scoped control subscriptions receive updates consistently across
  tmux versions.** (#17)

## 0.0.1-alpha.11 — 2026-09-12

### Added

- **`Server` finds one object without a stream.** `server.session(String)`,
  `session(SessionId)` and `pane(PaneId)` answer with an `Optional`, and
  `window(WindowContext)` names one winlink exactly. Each costs one capture, so
  `server.session("work").orElseGet(() -> server.newSession("work"))` replaces
  asking `hasSession` and then scanning `sessions()` — two reads with a gap in
  which the session can arrive or leave. Absence is empty rather than an
  exception, because whether a miss is a bug belongs to the caller. (#15)

- **`Server.windows(WindowId)` answers with every link of one window.** A window
  linked into several sessions is one window and several winlinks, each with its
  own index, so this returns all of them rather than whichever tmux listed
  first. Name one exactly with `Server.window(WindowContext)`. (#15)

- **`Pane.awaitText(String, Duration)` and `Pane.await(Predicate, Duration)`
  wait for a pane.** Both answer with a `WakeReason`, so a server that went away
  mid-wait stays distinguishable from nothing having printed. The timeout bounds
  the whole wait, reads included: each read is given only what is left of it,
  no read starts after the deadline, and text that appears late is not reported.
  The first read alone is allowed at least 250 ms, so a zero timeout can still
  answer that the text is already there. An interrupt throws
  `InterruptedException` rather than reading as a timeout. `await` captures the
  pane again before each test and hands the condition an ordinary handle on the
  caller's server. Reach for `Server.channel` first: a signal is exact, and
  reading the screen is a guess. (#15)

- **`Server.isAlive(Duration)` and `Server.killServer(Duration)` bound one
  call.** The only per-call deadline was `Server.cmd(List, Duration)`, so a
  caller that has to finish — a fixture confirming its server is gone, a
  shutdown path that cannot hang — left the typed API to get one. Both commands
  `killServer` issues are bounded, including the second look that confirms the
  kill. These overloads make `server::isAlive` and `server::killServer` inexact
  method references: a call site passing one where two functional interfaces are
  applicable now needs an explicit lambda. (#15)

### Changed

- **`Pane.sendKeys(List, boolean)` is replaced by `Pane.sendKeys(List)` and
  `Pane.sendLiteral(List)`.** The boolean chose whether tmux resolved each entry
  as a key name, so `pane.sendKeys(keys, true)` did not say at the call site
  whether `C-c` interrupted the pane or typed three characters. Write
  `sendKeys(keys)` for key names and `sendLiteral(keys)` for the characters they
  spell. (#15)

- **`Window.setSynchronizePanes(boolean)` is replaced by
  `Window.synchronizePanes()` and `Window.stopSynchronizingPanes()`.** Write
  whichever one the call site means, as `Pane.pipeTo` and `Pane.stopPiping`
  already do. (#15)

### Security

- **`TmuxFormats` is public, so a caller can make its own values literal.** tmux
  expands `#{...}` and `#(...)` before any shell sees a command, and `#(...)`
  runs one: measured on tmux 3.7d, a `#(...)` inside single quotes ran through
  both `run-shell` and `pipe-pane`, so shell quoting does not contain it. The
  library applies `TmuxFormats.literal` to every argument it composes itself,
  but `Pane.pipeTo`, `Window.displayPopup`, `Server.runShell`,
  `Server.runShellCapturing`, `Server.ifShell` and `Options.setExpanded` take
  text the caller composes, where expansion is sometimes the point. Apply
  `TmuxFormats.literal` to any value interpolated into one of those. No MCP tool
  reaches one of those positions with model-supplied text; `SECURITY.md` records
  the rule. (#15)

## 0.0.1-alpha.10 — 2026-09-06

No entries for `libtmux`.

## 0.0.1-alpha.9 — 2026-09-06

### Fixed

- **`Pane.breakOut` preserves literal `#` in requested window names.** The
  `break-pane -n` path receives the raw name; only the tmux 3.7 rename fallback
  applies tmux format literalization.

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

- **`ProcessTransport` now bounds concurrency, input writes, output, deadlines,
  and cleanup.** Configure its limits through the constructors; timeouts
  surface as `TmuxTimeoutException` with dispatch certainty. A deadline or
  cancellation during input terminates the child instead of blocking. (#6)

- **`Buffers.delete` now requires tmux 3.4 and reports a missing name.** tmux
  3.2a and 3.3a can delete the top buffer when the named buffer is absent, so
  the library refuses that unsafe operation. (#6)

### Fixed

- **`RowFormat` uses an explicit record terminator.** Multiline final fields
  and single-field rows no longer depend on physical line boundaries. (#6)

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

- **Handles refuse a replacement tmux server that reused an identifier.**
  Linked-window operations also retain the exact session and index they came
  from. (#6)

- **Snapshot-backed accessors reject a closed `Server`.** They no longer turn
  use after close into an empty hierarchy. (#6)

- **A live server with no sessions captures as an empty snapshot.** Child
  listings are not attempted when tmux has no current target. (#6)

### Removed

- **`ExecutionMode`, `ControlTransport`, `VirtualThreadTransport`,
  `LIBTMUX_MODE`, and their benchmark surface are removed.** `Server` uses
  process execution; use `ControlClient` for event streams and batches or
  command chains to reduce round trips. (#6)

## 0.0.1-alpha.7 — 2026-08-22

No entries for `libtmux`.

## 0.0.1-alpha.6 — 2026-08-16

No entries for `libtmux`.

## 0.0.1-alpha.5 — 2026-08-16

### Added

- **`ControlClient` surfaces what tmux volunteers.** `onEvent` publishes every
  notification a control client is sent, and `watch`/`unwatch` register a format
  for tmux to report when its value changes.

## 0.0.1-alpha.3 — 2026-08-16

No entries for `libtmux`.

## 0.0.1-alpha.2 — 2026-08-16

### Added

- **`Pane.retitle` gives a pane the title it reports.** `Pane.title()` could be
  read and not set, so setting one meant reaching past the API for
  `select-pane -T`. It returns a handle carrying the new value, the way
  `Window.rename` and `Session.rename` already do. A program running in the pane
  can still set its own title through an escape sequence, so what comes back says
  what the title is now and not what it will stay.

## 0.0.1-alpha.1 — 2026-08-16

### Fixed

- **A trailing semicolon ended a command under one carrier and not the other.**
  tmux ends a command at a semicolon ending *any* argument, not only at one
  standing alone, and a backslash before it keeps the semicolon. The control
  carrier quoted both, so `server.cmd(List.of("new-window", "-d", "-n",
  "grouped;", "list-windows"))` created a window under `DIRECT` and none under
  `CONTROL`. `ControlClient.isCommandGroup` is now the single reading of that
  rule and both carriers consult it. Measurements in
  [`docs/decisions/0009`](../docs/decisions/0009-command-groups-are-transport-agnostic.md).

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
