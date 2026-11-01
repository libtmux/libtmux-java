# Changelog

Notable changes that span more than one module, newest first: documentation, CI,
tests, build and release tooling, `libtmux-bom`, and the notes that cover a
whole release. A change to one module is in that module's changelog:

- [`libtmux`](libtmux/CHANGELOG.md)
- [`libtmux-mcp`](libtmux-mcp/CHANGELOG.md)
- [`libtmux-workspace`](libtmux-workspace/CHANGELOG.md)
- [`libtmux-workspace-cli`](libtmux-workspace-cli/CHANGELOG.md)
- [`libtmux-kotlin`](libtmux-kotlin/CHANGELOG.md)
- [`libtmux-scala`](libtmux-scala/CHANGELOG.md)
- [`libtmux-scala-cats`](libtmux-scala-cats/CHANGELOG.md)
- [`libtmux-scala-ox`](libtmux-scala-ox/CHANGELOG.md)
- [`libtmux-jackson`](libtmux-jackson/CHANGELOG.md)
- [`libtmux-junit5`](libtmux-junit5/CHANGELOG.md)

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports. The modules are released
together at one version, so a release has a section in each changelog from the
release that first shipped the module.

## Unreleased

## 0.1.2 — 2026-11-01

### Development

- **Test runs stay away from the tmux server a developer works in.** Since
  #117 a server handle with no socket lets tmux pick the server, which is the
  one in `TMUX` or the default socket, so a test that forgot its socket could
  kill the developer's own session. Every `Test` task of a library module, and
  each `run` in the `module-tests` builds, now starts without `TMUX`,
  `TMUX_PANE` and `LIBTMUX_SOCKET*`, in a private `TMUX_TMPDIR`. A test JVM
  uses that directory as its temporary directory, and refuses to start when it
  was not launched that way, as an IDE run is not. When a task ends, a
  finalizer stops any server on a socket in the directory and fails the build
  naming it. `examples/api/run.sh` does the same for the program it starts.
  (#123)

## 0.1.1 — 2026-11-01

### Changed

- **With no socket chosen, tmux picks the server, and `LIBTMUX_SOCKET_PATH` or
  `LIBTMUX_SOCKET` chooses another.** A `ServerConfig` built without an endpoint
  reads `LIBTMUX_SOCKET_PATH` (passed as `-S`), then `LIBTMUX_SOCKET` (`-L`), and
  passes tmux no socket when neither is set. The path wins when both are set, and
  a blank variable counts as unset. `tmux-workspace` takes `-S` and `-L` first,
  then the same variables, and no longer passes the `TMUX` socket as `-S`; tmux
  follows `TMUX` itself when no socket is chosen. `shell` hands tmuxp a socket a
  variable chose. See the
  [migration notes](MIGRATION.md#with-no-socket-chosen-tmux-picks-the-server).
  (#117)

- **CI tests the oldest and newest supported tmux on a push and a pull
  request.** 3.3a and 3.7c run on every push. The weekly schedule and a
  `workflow_dispatch` with `scope: full` run every supported release, the
  refusal lanes and the 3.8 and master previews, and the release workflow runs
  the whole matrix itself before it publishes. A dispatch also takes `tmux-ref` (a release, tag, branch or commit), `jdk` and
  `tmate`. (#115)

### Documented

- **The `Session` Javadoc groups its methods by task.** A "What is on here"
  section pairs each group's guidance with links to its methods. The `Server`
  groups gain the same paired guidance and link lists, and a group for
  checking the server and one for running a command.

## 0.1.0 — 2026-10-25

### Changed

- **Releases are numbered `0.1.N` from now on, with no `-alpha` tag.** The
  first is `0.1.0`, then `0.1.1`, `0.1.2` and so on, each incrementing `N`, so
  update tools no longer treat a release as a prerelease. A range such as
  `0.1.+` accepts every `0.1.N`, breaking releases included, so keep pinning an
  exact version. The README banners, the module READMEs and the POM
  description carry the new terms. Releases before this one keep their
  `0.0.1-alpha.N` numbers.

### Documented

- **The batching guide states the nested-command limit.** It lists the
  commands the guards do not read when nested, with the releases and failures.
  (#90)

- **The guides say exact 3.7 refuses every literal `break-pane`,** with or
  without `-n`, and keep the "name it or upgrade to 3.7a" advice for nested
  and key-bound `break-pane`. (#91)

## 0.0.1-alpha.19 — 2026-10-24

Optional tmux features that are unsafe on the connected daemon's release are
now refused before anything is sent; ordinary operations keep the 3.3a
minimum. A pane killed mid-wait ends as `PANE_GONE`, and `Pane.watchText`
returns the output seen so far.

### Changed

- **`Server.cmd`, `batch`, `chain` and `ControlClient.send` say that nested
  command strings are not checked.** An `if-shell` body, a hook or a
  `source-file` keeps tmux's own behaviour, so do not nest a command the
  library refuses. (#72)

### Development

- **CI no longer runs macOS.** macOS stays supported on a best-effort basis, and
  CI coverage for it is highly limited. The READMEs and the contributing guide
  say so. (#84)

## 0.0.1-alpha.18 — 2026-10-19

tmux 3.3a is now the minimum across the JVM modules and products. Upgrade the
running daemon as well as the client; an older one is refused with an error
that says to upgrade tmux or use the previous release. This release also
sections every README by task, with each API linked.

### Changed

- **tmux 3.3a is the minimum across the JVM modules and products.** Upgrade the
  running daemon as well as the client. Commands refuse older daemons with
  `UnsupportedFeatureException` before their requested effects, and the message
  says to upgrade tmux and restart any running server, or use the previous
  libtmux release. This includes 3.0 and 3.1, which cannot name their version. A
  version that names no release, such as `master`, fails with
  `MalformedResponseException`. The Python bridge also requires compatible
  Python libtmux. See [migration notes](MIGRATION.md). (#69)

### Documented

- **Every README opens with a quick start and is sectioned by task, with the
  API linked in each heading.** Each section shows a short example the
  documentation suite runs, and a reference-style link for every API, tool and
  command it names. The `libtmux-workspace-cli` reference for loading,
  document rules, imports, output and Python moved to
  `docs/guide/workspace-cli.md`, and the `libtmux-mcp` migration lists moved to
  `MIGRATION.md`; the READMEs link both. The root, `libtmux`, `libtmux-mcp` and
  `libtmux-workspace-cli` READMEs now say Linux, including WSL, and what CI runs
  on Ubuntu and macOS.

- **The root README heads each module, and `libtmux`'s README each package it
  exports, with a link to its directory on GitHub.** The module list and the
  package map stay above the headings. Under each heading are one line on what
  it is, its API reference on libtmux.org and javadoc.io, and an example the
  documentation suite compiles and runs. Every other module README now links
  its API reference, and the opening sections of the root and `libtmux`
  READMEs link each API where they first name it.

- **The `libtmux-mcp` README links every tool it lists to its page on
  libtmux.org, and sets out input checks, waits and limits as short bullets.**
  It also shows a Claude Code registration and a JSON client configuration that
  select a toolset. The `libtmux-workspace-cli` README adds `tmux-workspace`
  commands for `ls`, `search`, `convert` and `import`.

- **`SplitSpec.Builder.running`, `WindowSpec.Builder.running` and
  `SessionSpec.Builder.running` say that tmux reports the pane before the
  command has started.** Wait for the pane's command with `Pane.await` rather
  than reading it straight after creating the pane. (#35)

- **The Java `Server` Javadoc now has a "What is on here" overview.** It groups
  the methods by what they do, from connecting to the options and hooks tmux
  keeps, links each one, and names `cmd`, `run`, `batch` and `chain` for
  anything it does not list.

- **The Kotlin `Server` KDoc now has a "What is on here" overview.** It groups
  the members by what they do, from connecting to the options and hooks tmux
  keeps, links each one that Dokka can resolve, and names `cmd`, `run`,
  `batch`, `chain` and `asJava` for anything it does not list.

- **The Scala `Server` Scaladoc now has a description and a "What is on here"
  overview.** It groups the members by what they do, from connecting to the
  options and hooks tmux keeps, links each one that Scaladoc can resolve, and
  names `cmd`, `run`, `batch` and `chain` for anything it does not list.

### Development

- **The README type-link check now covers the BOM, Jackson, JUnit 5, MCP,
  workspace and workspace CLI READMEs, and the `libtmux-mcp` tests hold every
  tool a README names, in prose or a list, to a link to its page, including
  reference-style links.**

- **The README link check also follows absolute links to this repository's
  `master` branch, and holds the root and `libtmux` READMEs to a heading that
  links each module's or package's directory, to a bullet for each in the
  module list and package map, and to a link for each public type they name.**

- **The `libtmux-mcp` tests hold every documented `libtmux-mcp` flag,
  `LIBTMUX_` variable and client configuration to the launcher, and the
  `libtmux-workspace-cli` tests hold every documented `tmux-workspace` command
  to its parser.** The `libtmux-workspace-cli` tests also run the README's
  `load` and `freeze` example against tmux, and the `libtmux-mcp` tests hold
  every tool the README lists in a table or list to a link to its page.

## 0.0.1-alpha.17 — 2026-09-26

No user-visible behaviour changed since 0.0.1-alpha.16: this is the same code,
released from `master`.

## 0.0.1-alpha.16 — 2026-09-26

### Added

- **`ServerConfig.force256Colors` and `Server.Builder.force256Colors` start
  tmux clients with 256-color support.** `tmux-workspace load -2` sets it.
  Left unset, tmux detects the terminal. (#16)

### Fixed

- **Scala's `LiveView.snapshots`, `LiveView.snapshotsAfter` and Ox's
  `Flows.liveView` fail with the mirror's cause once its anchor session or
  server has gone.** They ended as if the view had been closed, so a caller
  could not tell a dead session from a quiet one, and they also stopped after a
  day with no change. (#23)

### Development

- **The preview lane runs tmux 3.8-rc2.** tmux replaced its `3.8-rc` tag with
  `3.8-rc2`, so the lane asked for a tag that no longer exists and reported
  nothing about the next release. (#26)

## 0.0.1-alpha.15 — 2026-09-26

### Added

- **Scala 3.9 facades release with the Java artifacts.**
  `libtmux-scala_3`, `libtmux-scala-cats_3` and `libtmux-scala-ox_3` publish
  from the same tag and version as `libtmux`, and `libtmux-bom` manages
  them. `io.github.libtmux.scaladsl.Server`, `Session`, `Window`, `Pane` and
  `Client` are the Java handles under opaque types, and every handle of both
  facades reaches its Java handle through `.asJava`. See [Getting
  started](docs/guide/scala/getting-started.md). (#23)

- **Scala: a typed query DSL, live state, and an Ox module.** Fields hang on
  each handle's companion (`Pane.command`), `&&`, `||` and `!` compose them,
  and `exactlyOne` and `atMostOne` answer a `CardinalityError`. `LiveView`
  and the Cats `LiveServer` signal wrap `ServerMirror`, starting from the
  current view. `libtmux-scala-ox` adds an Ox `Flow` over a subscription or a
  live view. (#23)

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

## 0.0.1-alpha.13 — 2026-09-20

### Added

- **Scala applications can use `libtmux-scala` for immutable collections and
  blocking tmux operations.** `libtmux-scala-cats` adds resource-scoped Cats
  Effect operations and FS2 observations. (#19)

## 0.0.1-alpha.12 — 2026-09-19

### Changed

- **Text waits distinguish existing text from new output.** This changes
  `Pane.awaitText` and MCP `wait_for_text`; see [migration
  guidance](MIGRATION.md#paneawaittext-answers-with-textoutcome-not-wakereason).
  (#17)

## 0.0.1-alpha.11 — 2026-09-12

### Documented

- **The streaming guide orders the waits, cheapest first.** `Server.channel` and
  tmux's `wait-for` appeared in no guide, so the deterministic server-side wait
  went unused while its heuristic alternative was written by hand three times.
  `docs/guide/streaming.md` now names each rung and what it costs, and says why
  a longer text wait is less reliable rather than more. (#15)

### Development

- **Two real-tmux tests no longer fail on a loaded machine.**
  `ExamplesRunTest` read a pane's command while its shell's startup files were
  still running, and `McpLauncherTest` gave a launcher JVM five seconds to exit
  when the property under test is that it exits at all. (#15)

- **Every CI job carries a timeout.** The `tmux 3.3a` lane ran for six hours
  against a median under two minutes before GitHub's ceiling stopped it. Each
  job now fails in minutes instead. (#14)

## 0.0.1-alpha.10 — 2026-09-06

No entries that span modules.

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

### Changed

- **The MCP guide maps every earlier public tool, resource URI, prompt workflow,
  and completion path.** Each retired name now points to its current typed
  route, composed workflow, or explicit no-replacement boundary.

### Fixed

- **`Pane.sendKeys` preserves option-shaped input.** It ends tmux option parsing
  before caller keys, so values such as `-X`, `-R`, and `-N` reach pane programs
  through the core API and MCP single or batch routes.

## 0.0.1-alpha.8 — 2026-08-30

### Changed

- **Filter expressions are immutable, model-bound wire values.** Build fields
  and relations through `Fields` or generated handles, and pass the matching
  `FilterModel` to `FilterJson.write*`; `EntityMetamodel`, `FieldProvenance`,
  and `FieldRef.name()` are removed. (#6)

### Fixed

- **`Pane.sendLine`, command-chain input, and `tmux_run` deliver text plus
  Enter as one literal operation.** Option-shaped input remains data, and
  concurrent calls cannot interleave their input. (#6)

## 0.0.1-alpha.7 — 2026-08-22

### Documented

- **`CONTRIBUTING.md` has moved to `.github/CONTRIBUTING.md`.** The policy an
  agent or contributor reads is now split three ways: `AGENTS.md` routes and
  carries only what applies to every change, `.github/WRITING.md` covers how
  this project writes, and `.github/CONTRIBUTING.md` covers how it works. A
  link to the old path needs updating; GitHub finds the new one on its own.

## 0.0.1-alpha.6 — 2026-08-16

### Added

- **`FilterModel.fieldNames` and `relationNames` say what a document may name.**
  The useful thing to tell a caller whose field was not recognised is which ones
  exist, and nothing outside the class could find that out. `libtmux-mcp` now puts
  the pane model's fields in `tmux_list_panes`'s own description and repeats them
  when a filter will not read — measured against a real agent, which guessed a
  plain field map and spent a call discovering the shape.

## 0.0.1-alpha.5 — 2026-08-16

No entries that span modules.

## 0.0.1-alpha.3 — 2026-08-16

### Documented

- **A `:` or `.` in a session or window name does three different things across
  the supported range.** 3.2a through 3.6 rewrite each one to `_` in a session
  name, 3.7 refuses the name outright, and 3.7a onwards keeps it — where it can
  no longer address the object, because a target splits on both. A window name is
  never rewritten, only kept or refused. `Server.newSession` and `Window.rename`
  now say so, and the behaviour is asserted on every lane.

## 0.0.1-alpha.2 — 2026-08-16

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

- `libtmux-bom`, so a consumer names a version once.

- Continuous integration: `check` on JDK 21 and 25, and the real-tmux suite
  against each of the eight supported tmux releases.

- `LICENSE`. The published POM had declared MIT since the first release
  metadata, with nothing in the repository to back it.

### Changed

- **The group is `io.github.libtmux`**, verified on the Central Portal, and the
  version is `0.0.1-alpha.1`. `alpha` is the lowest qualifier Maven's comparator
  recognises, so nothing published later can sort beneath it.

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
