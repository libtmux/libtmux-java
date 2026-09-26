# Changelog

Notable changes, newest first. Versions follow [semantic versioning][semver].

**This project is alpha.** Releases carry an `-alpha` prerelease tag. The API is
not settled, and any release may change or remove exported identifiers without a
deprecation period. Only the newest release is supported, and there are no
backports. Pin an exact version rather than a range. Not recommended for
production.

[semver]: https://semver.org/spec/v2.0.0.html

## Unreleased

### Added

- **`tmux-workspace` is on Maven Central as
  `io.github.libtmux:libtmux-workspace-cli`.** It releases with the other
  artifacts, at the same version, and `libtmux-bom` manages it. Its main class
  is `io.github.libtmux.workspace.cli.Main`. (#23)

- **`tmux-workspace` discovers, loads, freezes, converts, imports and searches
  workspaces.** Command names and flags follow tmuxp 1.74.0. Every command
  answers in JSON or NDJSON on request, and the CLI adds terminal progress,
  diagnostics, and Bash, Zsh and Fish completion. (#16)

- **`tmux-workspace load` checks every input before it builds anything.** It
  supports pane commands, directories, environment, options, window indexes,
  focus, scripts, attaching and appending. A failed load removes the sessions
  it created, and an interrupted load or a failed append reports what it left
  behind. (#16)

- **`tmux-workspace` checks a workspace's layouts against the target tmux
  before creating anything.** Presets resolve by that tmux's version, and pane
  counts must fit. On tmux 3.8 and later, a JSON layout's cell structure is
  checked too. (#16)

- **`tmux-workspace` runs tmuxp Python extensions and shells.** That bridge
  needs tmuxp 1.74.0 and uses the selected tmux binary; native commands never
  start Python. (#16)

- **`ServerConfig.force256Colors` and `Server.Builder.force256Colors` start
  tmux clients with 256-color support.** `tmux-workspace load -2` sets it.
  Left unset, tmux detects the terminal. (#16)

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

- **MCP `select_layout` accepts a unique abbreviation or a checksummed saved
  layout**, not only an exact built-in name. Mirrored main layouts still need
  tmux 3.5, and malformed layout syntax is refused before the window is looked
  up. (#16)

- **`WorkspaceBuilder` rebalances the window after each pane split**, so a
  window with more than four panes builds at the default terminal size. (#16)

- **`WorkspaceBuilder` refuses a layout with fewer cells than the window has
  panes**, checked against the target tmux before any window is created. (#16)

- **`WorkspaceBuilder` accepts a layout captured from a JSON-layout session**,
  refusing it only when the target tmux predates 3.8, rather than rejecting
  every JSON layout as an unknown name. (#16)

- **Classic layout validation accepts a layout longer than 8191 characters**,
  still checking its checksum, geometry, depth and pane count. (#16)

### Development

- **The preview lane runs tmux 3.8-rc2.** tmux replaced its `3.8-rc` tag with
  `3.8-rc2`, so the lane asked for a tag that no longer exists and reported
  nothing about the next release. (#26)

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
  [`docs/reference/operations.md`](docs/reference/operations.md) is generated
  from it. (#23)

- **More pane, window and session fields are queryable.** `Pane_.atTop()`,
  `atBottom()`, `atLeft()` and `atRight()`, `Window_.width()`, `height()` and
  `paneCount()`, and `Session_.windowCount()`. The field list ships in the
  jar at `META-INF/io.github.libtmux/field-catalog.tsv`. (#23)

- **`Delivery.kept` fails a read on a gap.** A reader that needs every event
  gets the value, or `lost N` when the buffer discarded some. (#23)

- **`info()` is the captured moment.** `Session`, `Window` and `Pane`
  return the snapshot record their accessors read. (#23)

- **`FakeTmux` answers control clients.** A test can attach a control client
  to it and push output and notifications to that client, without tmux.
  (#23)

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

- **Scala 3.9 facades release with the Java artifacts.**
  `libtmux-scala_3`, `libtmux-scala-cats_3` and `libtmux-scala-ox_3` publish
  from the same tag and version as `libtmux`, and `libtmux-bom` manages
  them. `io.github.libtmux.scaladsl.Server`, `Session`, `Window`, `Pane` and
  `Client` are the Java handles under opaque types, and every handle of both
  facades reaches its Java handle through `.asJava`. See [Getting
  started](docs/guide/scala/getting-started.md). (#23)

- **Scala Cats calls cancel.** Every Cats call, `Batch`, `CommandChain` and
  `Channel` included, can be interrupted; a canceled call ends in
  `Outcome.Canceled`, and fs2 streams wait without a blocking pool. Handles
  have `Eq`, `Hash` and `Show` instances, and `TmuxVersion` an `Order`.
  (#23)

- **Scala: a typed query DSL, live state, and an Ox module.** Fields hang on
  each handle's companion (`Pane.command`), `&&`, `||` and `!` compose them,
  and `exactlyOne` and `atMostOne` answer a `CardinalityError`. `LiveView`
  and the Cats `LiveServer` signal wrap `ServerMirror`, starting from the
  current view. `libtmux-scala-ox` adds an Ox `Flow` over a subscription or a
  live view. (#23)

- **An MCP tool error carries `error_code` and `retryable`.** `error_code`
  names the failure's branch of `LibTmuxException`, `REFUSED` for an argument
  or state the tool rejects, or `INTERNAL_ERROR`; `retryable` is the
  failure's `safeToRetry()`. Both are in `_meta`, so a client validating
  `structuredContent` against a tool's output schema still accepts an error.
  (#23)

- **Published modules record their version in `module-info`.** A stack trace
  names `io.github.libtmux@<version>`. (#23)

- **`libtmux-kotlin` and the Scala artifacts publish a CycloneDX SBOM,** as
  the Java artifacts do, under the same `cyclonedx` classifier. (#23)

### Changed

- **JDK 25 is the floor, not JDK 21.** Every module, the Kotlin and Scala
  targets, and CI moved together. See [migration
  guidance](MIGRATION.md#jdk-25-is-the-floor). (#23)

- **Every failure is one sealed tree in `io.github.libtmux.exception`.**
  `switch`, `when` and `match` over `LibTmuxException` are checked for
  exhaustiveness, several types are renamed, and tmux refusing a command is
  `CommandRejectedException`. A failure carries the command, its exit status
  and what tmux printed; its message quotes at most 240 characters of that.
  `libtmux-jackson`'s `SchemaException` is an `IllegalArgumentException`
  now, outside the tree. See [migration
  guidance](MIGRATION.md#failures-are-one-sealed-tree-in-iogithublibtmuxexception).
  (#23)

- **A handle used after its `Server` closed throws `ServerClosedException`.**
  It sits outside the sealed tree, and `outcome()` says whether a command the
  close interrupted may have reached tmux. (#23)

- **`Notification` has four more cases.** `%pause`, `%continue`, `%message`
  and `%config-error` arrive as `Notification.Pause`, `Continue`, `Message`
  and `ConfigError` instead of `Unknown`, so an exhaustive `switch` needs
  them. See [migration
  guidance](MIGRATION.md#notification-has-four-more-cases). (#23)

- **An `EventSubscription` has one reader.** A read that overlaps another, or
  any read after `stream()` or `publisher()` took it, throws
  `IllegalStateException`. Subscribe again for a second reader. See
  [migration guidance](MIGRATION.md#an-eventsubscription-has-one-reader).
  (#23)

- **`EventSubscription.next` returns a `Delivery`.** A full buffer's next
  read is a `Delivery.Gap` naming how many events were discarded; `cause()`
  says why the client ended the subscription. See [migration
  guidance](MIGRATION.md#eventsubscriptionnext-returns-a-gap-before-the-events-that-remain).
  (#23)

- **Filtered reads are applied by tmux.** `sessions`, `windows` and `panes`
  with a `FilterExpr`, and the name, id and expression lookups, send what tmux
  can evaluate as a `-f` format and read only the matching sessions, in two
  tmux commands whether or not anything matches. Relations and `matches`,
  whose regular expressions tmux reads in another dialect, stay local; what
  comes back is still tested against the expression. (#23)

- **`Client.refresh()` returns the client, and throws `TargetGoneException`
  once it has detached,** as every other handle does. See [migration
  guidance](MIGRATION.md#clientrefresh-returns-the-client-or-throws). (#23)

- **A handle is refused by a tmux restarted on its pid.** A capture records
  when its server started, and every handle command, capture fence and
  control attach compares it with the pid. `ServerSnapshot.serverStartTime()`
  reports it. (#23)

- **`Server.control` attaches to the tmux a capture named.** A socket reused
  by a new server is refused. `ControlClient.attach(config, session)` is now
  `attachUnfenced`. See [migration
  guidance](MIGRATION.md#an-attachment-that-skips-the-incarnation-check-says-so).
  (#23)

- **Server-wide scopes moved off `Server`.** The command catalog is
  `server.commands().list()`, shell commands are `server.shell()`, the
  message log is `server.messageLog().lines()`, and prompt history is
  `server.prompt()`. See [migration
  guidance](MIGRATION.md#prompt-history-is-serverprompt). (#23)

- **`killServer` returns after the daemon exits,** so a server started
  straight afterwards cannot reach the dying one. (#23)

- **Kotlin reads the core's collections as read-only.** A list, set or map
  the core returns is a Kotlin `List`, `Set` or `Map`, so code that called
  `add` on one no longer compiles; the call threw before. See [migration
  guidance](MIGRATION.md#kotlin-reads-core-collections-as-read-only). (#23)

- **`libtmux-kotlin` is wrapper classes, not extensions on the Java types.**
  `Server`, `Session`, `Window`, `Pane`, `Client` and `ControlClient` in
  `io.github.libtmux.kotlin` make every tmux operation `suspend` and every
  captured value a property; each answers its Java handle as `asJava`, and
  `Server.fromJava` wraps one opened in Java. It is readable from Kotlin 2.1.
  See [migration
  guidance](MIGRATION.md#libtmux-kotlin-is-wrapper-classes-now-not-extensions-on-the-java-types).
  (#23)

- **`search_panes` reports a budget cut as `truncated`, not `limited`,**
  matching every other MCP read tool. See [migration
  guidance](MIGRATION.md#search_panes-reports-truncated-not-limited). (#23)

- **The MCP server's instructions define "attended" and say what is absent
  on purpose.** Hook writes, environment writes and buffer reads are named as
  choices, with what to do instead. (#23)

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

- **The MCP server's instructions reach the model whole.** They exceeded
  the 2048 bytes Claude Code reads, which dropped the rest without saying
  so. (#23)

- **An MCP tool that fails unexpectedly still answers as a tool error,** not
  a JSON-RPC internal error a client may never show the model. (#23)

- **MCP refusals and failures name the recovery.** A pane-input refusal, a
  kill tool's failed self-check, and a result too large to send each say
  what to do next. (#23)

- **`TmuxExtension` reaps a fixture whose pid was reused.** A killed run's
  fixture is named by its JVM's start as well as its pid, so a live process
  that later holds the pid no longer keeps it. (#23)

### Removed

- **`Server.setMouseEnabled`.** Write `globalOptions().set("mouse", "on")`.
  See [migration guidance](MIGRATION.md#serversetmouseenabled-is-gone). (#23)

- **The Kotlin extensions on the Java types,** such as
  `Session.activeWindowOrNull` and `Options.getOrNull`. The wrapper classes'
  members replace them. (#23)

### Documented

- **Two guides for code that drives tmux from a service.** [Threads,
  cancellation, and what runs at once](docs/guide/concurrency.md) says which
  calls may run together and how a deadline or an interrupt ends one;
  [Failures, telemetry, and pane input](docs/guide/operating-a-service.md)
  covers resending a failed command, per-command reports and pane holds.
  (#23)

- **A guide set for the Scala facades** under
  [`docs/guide/scala/`](docs/guide/scala/getting-started.md): installing,
  ownership, execution, queries, streaming and compatibility. (#23)

## 0.0.1-alpha.14 — 2026-09-20

### Changed

- **Scala facade artifacts use named top-level module roots.** Development
  builds, documentation, and consumer checks now consistently use
  `libtmux-scala` and `libtmux-scala-cats`. (#21)

- **`libtmux-bom` no longer advertises separately released Scala artifacts.**
  Pin a Scala artifact version directly; a Java release does not imply a
  matching Scala release. (#21)

### Fixed

- **`ObserveChanges` counts loss across every notification.** The runnable
  Scala example verifies deliberate overflow and reconciles current state
  without assuming every notification is a window rename. (#21)

## 0.0.1-alpha.13 — 2026-09-20

### Added

- **Scala applications can use `libtmux-scala` for immutable collections and
  blocking tmux operations.** `libtmux-scala-cats` adds resource-scoped Cats
  Effect operations and FS2 observations. (#19)

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

- **`FakeTmux` tests library consumers without starting tmux.** It is available
  in `libtmux-junit5`. (#17)

- **The core artifact declares the Java module `io.github.libtmux`.** Internal
  packages are encapsulated on the module path. (#17)

- **`Pane.dead` reports whether a pane process has exited.** (#17)

- **`Pane.position` reports a pane's terminal coordinates.** (#17)

- **`Window.previousLayout` cycles backward through preset layouts.** (#17)

- **Command names can be logged through `System.Logger` at `DEBUG`** without
  exposing arguments. (#17)

- **MCP `send_keys` and `send_keys_batch` accept `enter`** to submit literal
  text in one call. (#17)

### Changed

- **Failed reads throw instead of returning false or empty results.** An absent
  daemon raises `ServerNotRunningException`; see [migration
  guidance](MIGRATION.md#failed-live-reads). (#17)

- **`server.keys()` replaces `bindKey`, `unbindKey`, and `listKeys`.** See
  [migration guidance](MIGRATION.md#key-bindings-are-a-view-serverkeys). (#17)

- **Text waits distinguish existing text from new output.** This changes
  `Pane.awaitText` and MCP `wait_for_text`; see [migration
  guidance](MIGRATION.md#paneawaittext-answers-with-textoutcome-not-wakereason).
  (#17)

- **`Window.layout` distinguishes classic and JSON layouts.** See [migration
  guidance](MIGRATION.md#windowlayout-answers-a-windowlayout). (#17)

- **Control notifications expose typed events and identifiers.** See the
  [migration guide](MIGRATION.md). (#17)

- **`Pane.pid` reports an absent process explicitly.** See [migration
  guidance](MIGRATION.md#panepid-returns-optionallong). (#17)

- **`raiseIfDead` becomes `requireAlive`; renamed exceptions end in
  `Exception`.** See [migration
  guidance](MIGRATION.md#exception-and-guard-names). (#17)

- **Interactive pane chooser methods are removed.** Use `Server.cmd` for
  attached-terminal commands; see [migration
  guidance](MIGRATION.md#the-interactive-chooser-methods-on-pane-are-removed).
  (#17)

- **The keyword filter parser is removed.** Use typed filters or `FilterJson`;
  see [migration guidance](MIGRATION.md#legacyfilters-is-removed). (#17)

- **Snapshot construction requires server identity.** See [migration
  guidance](MIGRATION.md#serversnapshotof-needs-the-servers-identity). (#17)

- **Error Prone flags discarded replacement handles.** Retain rename, retitle,
  and refresh results; see [migration
  guidance](MIGRATION.md#discarded-replacement-handles). (#17)

- **Control clients request JSON layout notifications when tmux supports them.**
  See [migration
  guidance](MIGRATION.md#controlclientattach-requests-json-layouts-on-connect).
  (#17)

### Fixed

- **Text waits discount recognized echoes of library input.** Exact output
  repeats and partial redraws remain ambiguous; wait for a cold shell's prompt
  before typing. (#17)

- **Pane waits find text wrapped across terminal rows.** (#17)

- **Non-ASCII arguments and output survive non-UTF-8 JVM locales.** See
  [migration guidance](MIGRATION.md#non-ascii-text-reaches-tmux-on-any-locale).
  (#17)

- **Caller text beginning with a dash stays positional.** This includes typing,
  shell commands, creation programs, popups, and pipes; raw `cmd` arguments
  remain caller-controlled. (#17)

- **Forgotten server and control-client handles no longer keep the JVM running
  indefinitely.** (#17)

- **Cancelling a channel wait consistently throws `InterruptedException`.** See
  [migration
  guidance](MIGRATION.md#a-cancelled-channel-wait-throws-interruptedexception).
  (#17)

- **Development and release-candidate tmux version strings can be read.** (#17)

- **tmux 3.3 supports prompt history, window start directories, and detached
  session sizes** without requiring the later 3.3a patch. (#17)

- **Sized sessions can be created before the tmux server is running.** (#17)

- **Layout selection accepts JSON layouts and unique preset prefixes when
  supported by tmux.** Unsupported layouts are refused before dispatch. (#17)

- **MCP rename reports the name tmux actually assigned.** (#17)

- **Failed commands report tmux diagnostics or the process-launch reason.**
  (#17)

- **Session-scoped control subscriptions receive updates consistently across
  tmux versions.** (#17)

- **MCP stop keys cannot interleave with newly admitted typing.** Active runs
  can still be interrupted. (#17)

- **`NamedServerFixture` accepts platform temporary-directory symlinks.** (#17)

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

### Fixed

- **The attach command an MCP client is handed covers every kind of tmux
  socket.** It was re-derived in two places, each naming `-S` and `-L` itself,
  so an endpoint kind those branches did not enumerate produced a command
  missing the flag that selects the server. The socket path tmux reported still
  wins whenever there is one, since `-L name` is resolved again under the
  operator's own `TMUX_TMPDIR`; otherwise the command comes from
  `ServerEndpoint.flags()`. Only a path a shell would mangle is quoted. (#15)

- **`show_environment` reports a removed variable instead of dropping it.** tmux
  prints a variable removed with `set-environment -r` as `-NAME`, with no `=`,
  and those lines were discarded, so a removed variable read exactly like one
  never set. They now arrive in a new `unset` list beside `variables`. (#15)

### Documented

- **The streaming guide orders the waits, cheapest first.** `Server.channel` and
  tmux's `wait-for` appeared in no guide, so the deterministic server-side wait
  went unused while its heuristic alternative was written by hand three times.
  `docs/guide/streaming.md` now names each rung and what it costs, and says why
  a longer text wait is less reliable rather than more. (#15)

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

### Development

- **Two real-tmux tests no longer fail on a loaded machine.**
  `ExamplesRunTest` read a pane's command while its shell's startup files were
  still running, and `McpLauncherTest` gave a launcher JVM five seconds to exit
  when the property under test is that it exits at all. (#15)

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
  [`docs/decisions/0006`](docs/decisions/0006-real-tmux-junit5-fixture-lifecycle.md).
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
  [`docs/decisions/0009`](docs/decisions/0009-command-groups-are-transport-agnostic.md).
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
