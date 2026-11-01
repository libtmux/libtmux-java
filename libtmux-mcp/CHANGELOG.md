# Changelog

Notable changes to `libtmux-mcp`.

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

No entries for `libtmux-mcp`.

## 0.1.1 — 2026-11-01

### Changed

- **`libtmux-mcp` no longer starts a server of its own on the socket
  `libtmux-mcp`.** With no `--socket`, `--socket-name`, `LIBTMUX_SOCKET_PATH` or
  `LIBTMUX_SOCKET` it passes tmux no socket and serves the server tmux picks. It
  no longer writes the minimal configuration, and `teardown` is out of the default
  toolsets for every launch. Setting `LIBTMUX_SOCKET` and `LIBTMUX_SOCKET_PATH`
  together no longer fails startup: the path wins. An empty `LIBTMUX_SOCKET` or
  `LIBTMUX_SOCKET_PATH` is unset where it used to fail. Pass
  `--socket-name libtmux-mcp` to keep the old socket. See the
  [migration notes](../MIGRATION.md#with-no-socket-chosen-tmux-picks-the-server).
  (#117)

## 0.1.0 — 2026-10-25

No entries for `libtmux-mcp`.

## 0.0.1-alpha.19 — 2026-10-24

### Fixed

- **`wait_for_text` and `run_shell_command` answer for a gone pane or server.**
  A pane killed mid-wait ends the call as `PANE_GONE` and a killed server as
  `SERVER_GONE`, both with the output seen so far, where the tool used to
  return an error or wait out the timeout. A bounded check of the pane runs
  on an interval while `run_shell_command` blocks, so a killed pane ends it
  within about a quarter second; the check reads through `list-panes` and
  `capture-pane`, so it does not run an `after-display-message` hook. `matched_line` for a match split
  across wrapped rows is now only the rows that carry it. (#72)

## 0.0.1-alpha.18 — 2026-10-19

### Added

- **The MCP server states the tmux floor.** Its instructions and
  `tmux://capabilities` (`minimumTmux`) name 3.3a. Against an older tmux,
  startup prints one `libtmux-mcp:` line and exits non-zero without a stack
  trace. (#69)

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux-mcp`.

## 0.0.1-alpha.16 — 2026-09-26

### Changed

- **MCP `list_windows` and `show_environment` take `session_id` or
  `session_name` in place of `session`.** A model passing the `$1` that
  `list_sessions` just gave it was told no session had that name. For
  `show_option` and `show_hooks`, a session-scope `target` is now an ID or a
  name. (#23)

### Fixed

- **MCP `select_layout` accepts a unique abbreviation or a checksummed saved
  layout**, not only an exact built-in name. Mirrored main layouts still need
  tmux 3.5, and malformed layout syntax is refused before the window is looked
  up. (#16)

## 0.0.1-alpha.15 — 2026-09-26

### Added

- **An MCP tool error carries `error_code` and `retryable`.** `error_code`
  names the failure's branch of `LibTmuxException`, `REFUSED` for an argument
  or state the tool rejects, or `INTERNAL_ERROR`; `retryable` is the
  failure's `safeToRetry()`. Both are in `_meta`, so a client validating
  `structuredContent` against a tool's output schema still accepts an error.
  (#23)

### Changed

- **`search_panes` reports a budget cut as `truncated`, not `limited`,**
  matching every other MCP read tool. See [migration
  guidance](../MIGRATION.md#search_panes-reports-truncated-not-limited). (#23)

- **The MCP server's instructions define "attended" and say what is absent
  on purpose.** Hook writes, environment writes and buffer reads are named as
  choices, with what to do instead. (#23)

### Fixed

- **The MCP server's instructions reach the model whole.** They exceeded
  the 2048 bytes Claude Code reads, which dropped the rest without saying
  so. (#23)

- **An MCP tool that fails unexpectedly still answers as a tool error,** not
  a JSON-RPC internal error a client may never show the model. (#23)

- **MCP refusals and failures name the recovery.** A pane-input refusal, a
  kill tool's failed self-check, and a result too large to send each say
  what to do next. (#23)

## 0.0.1-alpha.14 — 2026-09-20

No entries for `libtmux-mcp`.

## 0.0.1-alpha.13 — 2026-09-20

No entries for `libtmux-mcp`.

## 0.0.1-alpha.12 — 2026-09-19

### Added

- **MCP `send_keys` and `send_keys_batch` accept `enter`** to submit literal
  text in one call. (#17)

### Fixed

- **MCP rename reports the name tmux actually assigned.** (#17)

- **MCP stop keys cannot interleave with newly admitted typing.** Active runs
  can still be interrupted. (#17)

## 0.0.1-alpha.11 — 2026-09-12

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

### Fixed

- **Destructive MCP tools fail closed when caller-pane identity cannot be
  proven.** Uncertain socket, server, or pane identity now requires explicit
  self-confirmation instead of bypassing the guard. (#6)

- **MCP pane cursors are authenticated and bound to daemon and pane identity.**
  Reads preserve continuity across bounded history compaction when it can be
  proven, and pane capture uses identity-fenced batches with strict outcome
  checks. (#6)

- **`tmux_run` requires a POSIX shell and uses a 128-bit completion marker.**
  It refuses another foreground program instead of sending shell framing that
  program cannot interpret. (#6)

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

## 0.0.1-alpha.7 — 2026-08-22

No entries for `libtmux-mcp`.

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

No entries for `libtmux-mcp`.

## 0.0.1-alpha.2 — 2026-08-16

No entries for `libtmux-mcp`.

## 0.0.1-alpha.1 — 2026-08-16

### Added

- **A model can narrow `tmux_list_panes` with a filter.** The MCP server accepts
  the same versioned filter document every port of libtmux reads, so
  `pane_current_command starts_with nvim` selects panes without the model
  reasoning over the whole listing. One capture either way: filtering happens
  over what it already returned. The example in the tool's own description is a
  constant the test suite parses, so it cannot drift from the schema.

- `TmuxTools.describe(Collection<Pane>)`, replacing a filtering overload that
  would have read as though tmux did the selecting.

### Changed

- The MCP server reports the version from its jar manifest instead of a literal,
  which had already drifted to `0.1.0`.
