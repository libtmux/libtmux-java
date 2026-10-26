<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java MCP">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-mcp

**A tmux server, exposed to a model over the Model Context Protocol.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/java/latest/mcp/)
[![libtmux-mcp](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-mcp?label=libtmux-mcp)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-mcp)

</div>

Point Claude Code, Claude Desktop, Codex, or any MCP client at a tmux socket. It
can [list what is running][tool-list-panes], [read a pane][tool-capture-pane],
[run a command and wait for it][tool-run-shell-command], and build a session
with typed calls such as [`create_session`][tool-create-session] and
[`split_window`][tool-split-window].

`io.github.libtmux:libtmux-mcp` — [on Maven
Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-mcp).
API reference: [libtmux.org][mcp-reference] and [one page for each
tool][mcp-tools] for the current trunk,
[javadoc.io](https://javadoc.io/doc/io.github.libtmux/libtmux-mcp) for a
released version.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.
>
> Tool names and shapes are part of the API.

**Contents** — [Quick start](#quick-start) · [Read
panes](#find-your-way-and-read-panes) · [Run a command](#run-a-command-and-wait)
· [Type](#type-into-a-pane) · [Build a session](#build-a-session) ·
[Limits](#limit-what-a-client-can-do) · [Tools](#the-tools) ·
[Support](#support-and-status)

## Quick start

Build the launcher. An MCP client starts it as a subprocess and speaks JSON-RPC
over its stdin and stdout, so there is nothing to keep running:

```console
$ ./gradlew :libtmux-mcp:installDist
```

That writes `libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp`. Commands
below assume its `bin` directory is on `PATH`; otherwise substitute the full
path.

### `claude mcp add`: tell Claude Code about it

`--socket` pins the tmux server by its socket path:

```console
$ claude mcp add tmux -- /absolute/path/to/libtmux-mcp --socket /tmp/my-app/s
```

To offer only the tools that read tmux, put the toolset in the environment the
client starts the server with, and name the socket by name instead of path:

```console
$ claude mcp add tmux \
    -e LIBTMUX_TOOLSETS=inspect \
    -- /absolute/path/to/libtmux-mcp --socket-name work
```

### `codex mcp add`: the same for Codex

```console
$ codex mcp add tmux -- /absolute/path/to/libtmux-mcp --socket /tmp/my-app/s
```

### `claude_desktop_config.json`: the same for Claude Desktop

A client that reads a JSON configuration passes the flags as `args`:

```json
{
  "mcpServers": {
    "tmux": {
      "command": "/absolute/path/to/libtmux-mcp",
      "args": ["--socket", "/tmp/my-app/s"]
    }
  }
}
```

and the environment as `env`:

```json
{
  "mcpServers": {
    "tmux": {
      "command": "/absolute/path/to/libtmux-mcp",
      "args": ["--socket-name", "work"],
      "env": { "LIBTMUX_TOOLSETS": "inspect" }
    }
  }
}
```

Any other client that implements MCP's stdio transport can launch it the same
way. There is nothing to configure but the path and the flags.

### `--socket`, `--socket-name`, `--tmux`: choose which tmux it drives

| flag | what it chooses |
| --- | --- |
| `--socket <path>` | which tmux server, by socket path |
| `--socket-name <name>` | which tmux server, by name under tmux's own directory |
| `--tmux <binary>` | which tmux to run |

With no socket flag the launcher uses the socket named `libtmux-mcp`. If that
socket does not exist, it starts tmux with the package's minimal configuration
and enables all four toolsets. The choice is made once, when the server starts.

An existing or explicitly selected server may not have been configured by this
launcher, so `teardown` is left out of its default.

### Launcher check: see what a selection serves

Start the launcher with its input closed. It reports the toolsets and the tools
they select on standard error, then exits:

```console
$ LIBTMUX_TOOLSETS=inspect libtmux-mcp --socket-name my-project < /dev/null
```

## A conversation with an agent

> **You:** What's running in my tmux panes, and is the test suite still going?
>
> **Agent:** Three panes. `%1` is running `pytest`, on `tests/test_auth.py`: 84
> passed so far, nothing failed yet. Want me to wait for it to finish?

The agent reads the panes and types into them itself, so nobody pastes output
back and forth.

Three rules shape every tool:

- **Target by id, never by position.** `%1` is a pane, `@1` a window, `$1` a
  session. A bare `1` is refused, because tmux would read it as an index.
- **Wait, do not poll.** Every wait is a tool, so an agent never loops on a
  screen to guess whether a command finished.
- **Reads are bounded and say so.** Every read is capped, keeps the newest
  lines, and reports what it dropped.

## Find your way and read panes

### [`list_panes`][tool-list-panes]: what is running, and where

[`get_server_info`][tool-get-server-info],
[`list_sessions`][tool-list-sessions], [`list_windows`][tool-list-windows] and
[`list_panes`][tool-list-panes] answer with ids that other tools take:

| tool | gives back |
| --- | --- |
| [`get_server_info`][tool-get-server-info] | the pinned server's identity, version, and current state |
| [`list_sessions`][tool-list-sessions] | sessions, with stable `$id` values |
| [`list_windows`][tool-list-windows] | windows, with the `@id` other tools take |
| [`list_panes`][tool-list-panes] | panes, with the `%id` other tools take |
| [`get_session_info`][tool-get-session-info], [`get_window_info`][tool-get-window-info], [`get_pane_info`][tool-get-pane-info] | one target's metadata |
| [`find_pane_by_position`][tool-find-pane-by-position] | one pane at a named window corner |

[`list_panes`][tool-list-panes] reads metadata: what is running.
[`search_panes`][tool-search-panes] reads content: what is displayed.

### [`capture_pane`][tool-capture-pane]: what a pane shows

[`capture_pane`][tool-capture-pane] returns what a pane shows now, plus a
cursor. Its siblings read a pane or its configuration in other ways:

| tool | gives back |
| --- | --- |
| [`capture_pane`][tool-capture-pane] | what a pane shows now, plus a cursor |
| [`capture_since`][tool-capture-since] | **only what is new** since a cursor, plus the next cursor |
| [`snapshot_pane`][tool-snapshot-pane] | bounded content and pane metadata together |
| [`search_panes`][tool-search-panes] | which panes show bounded plain text or a bounded RE2 pattern |
| [`show_environment`][tool-show-environment], [`show_hooks`][tool-show-hooks], [`show_option`][tool-show-option] | selected configuration state |
| [`get_tmux_variables`][tool-get-tmux-variables] | a capped set of validated variable names |
| [`call_read_tools_batch`][tool-call-read-tools-batch] | up to sixteen typed inspect calls with full nested MCP results when they fit |

To read a pane without entering copy mode, which belongs to a person:

- Set `history: true` on [`capture_pane`][tool-capture-pane] or
  [`snapshot_pane`][tool-snapshot-pane] for bounded scrollback.
- Use [`search_panes`][tool-search-panes] to find displayed text.
- Continue from a cursor with [`capture_since`][tool-capture-since].

[`capture_since`][tool-capture-since] hands over finished lines only, so half a
line never passes for a whole one.

### [`search_panes`][tool-search-panes]: which pane mentions it

One call asks every pane at once. Plain text is the default, and `regex: true`
uses the bounded RE2 dialect:

```json
{
  "name": "search_panes",
  "arguments": {
    "pattern": "FAILED|ERROR",
    "regex": true,
    "max_matches_per_pane": 5,
    "max_lines": 50
  }
}
```

The answer's `truncated` field says when a limit stopped the search. Argument
defaults are on the [`search_panes`][tool-search-panes] page.

### [`call_read_tools_batch`][tool-call-read-tools-batch]: several reads in one call

Batch exact inspect calls instead of asking for one broad projection:

```json
{"name": "call_read_tools_batch", "arguments": {"operations": [
  {"tool": "list_panes", "arguments": {}},
  {"tool": "show_option", "arguments": {"scope": "server", "name": "status"}}
]}}
```

Each nested result stays whole. A row that would push the response past its
size cap stays in order with `result: null` and `resultTruncated: true`, and
the outer result sets `truncated`.

## Run a command and wait

### [`run_shell_command`][tool-run-shell-command]: a command you wrote

One call sends the command, waits, and returns the output and the exit status:

```json
{"name": "run_shell_command",
 "arguments": {"pane_id": "%1", "command": "pytest -q", "timeout": 120}}
```

```json
{"outcome": "SIGNALLED", "exit_status": 1, "output": ["...", "1 failed, 84 passed"]}
```

`outcome` says how the wait ended:

- `SIGNALLED`: the command finished. Only then is `exit_status` meaningful.
- `TIMED_OUT`: it was still running at the deadline.
- `PANE_GONE`: the pane was killed or exited while the command ran, so it did
  not finish. `output` holds what it had printed; the call does not wait out
  its timeout.
- `SERVER_GONE`: tmux itself died underneath the wait. `output` holds what the
  last look at the pane showed.

What to know about the command:

- It runs in a subshell of the pane's POSIX shell, so `cd` and `export` do not
  persist in that shell.
- Its status is still reported if it is interrupted or terminated.
- A command still running at the deadline keeps the pane.
  [`send_keys`][tool-send-keys] with only stop keys (`C-c`, `C-\`) is let
  through, because it ends the command.
- Shell functions that already exist and are named `trap`, `eval`, `exit`, or
  like the resolved tmux client are outside the guarantee.
- ASCII control characters, DEL included, are refused in the tmux executable
  and socket paths.

### [`wait_for_text`][tool-wait-for-text]: output you did not start

For a dev server, a daemon, or someone else's build, always pass `stop`:

```json
{"name": "wait_for_text",
 "arguments": {"pane_id": "%2", "patterns": ["Listening on"],
               "stop": ["error:", "EADDRINUSE"], "timeout": 60}}
```

- Without `stop`, a run that fails in the first second is waited on until the
  deadline, and you get a timeout instead of the error.
- Patterns are plain text unless you pass `regex`, so `[FAILED]` means those
  eight characters.
- Text already on the screen when a cursorless call starts is not a fresh
  match. The outcome is `PRESENT_AT_ENTRY`, not `MATCHED`.
- A pane killed mid-wait ends the call as `PANE_GONE`, and a killed server as
  `SERVER_GONE`. Both carry the output seen so far, and a second client left
  attached to the dying server does not change the answer. A bounded liveness
  check runs on an interval while the wait blocks, so a killed pane ends
  [`run_shell_command`][tool-run-shell-command] within about a quarter
  second; tmux's answer to that check, never the elapsed time, decides the
  outcome.
- A match split across a wrapped row is found, and `matched_line` holds only
  the rows that carry it.

### [`capture_since`][tool-capture-since]: watch across turns

Pass the cursor from a previous read, and only the finished lines added since
come back:

```json
{"name": "capture_since",
 "arguments": {"pane_id": "%1", "cursor": "<cursor from capture_pane>"}}
```

[`wait_for_channel`][tool-wait-for-channel] lets tmux itself block until a
command signals a channel, and [`signal_channel`][tool-signal-channel] is the
other end. The Java library still exposes control-mode subscriptions for a
long-lived event stream; the [streaming guide][streaming] covers it.

### Limits on every wait

- A wait is capped at 30 seconds by default and two minutes at most, and the
  answer reports the ceiling it enforced.
- A waiting call holds the agent's turn for up to the cap. The server keeps
  answering other calls meanwhile.
- A client's request deadline is separate. The Java SDK 2.0.1 client defaults
  to 20 seconds, so configure it above any longer wait you request.
- With that SDK, cancelling or timing out abandons the answer but does not stop
  the handler or undo tmux changes it already dispatched. Inspect state before
  retrying.

The full contract is on the [`run_shell_command`][tool-run-shell-command] and
[`wait_for_text`][tool-wait-for-text] pages.

## Type into a pane

### [`send_keys`][tool-send-keys]: keys, checked first

[`send_keys`][tool-send-keys], [`send_keys_batch`][tool-send-keys-batch] and
[`paste_text`][tool-paste-text] type into a pane. Each is checked before it
types:

- [`send_keys`][tool-send-keys] refuses the whole send if any pane that would
  receive it is in a mode, is dead, is the pane this conversation runs in, or is
  shown by a terminal client. Panes synchronized with the target count as
  recipients.
- [`paste_text`][tool-paste-text] applies the same check to its target only.
- [`run_shell_command`][tool-run-shell-command] needs one live shell and refuses
  a synchronized cohort of more than one pane.
- The check observes the panes before the send. `resolved_pane_ids` lists the
  configured recipients and does not confirm delivery.

The full contract is on the [Java MCP topics][mcp-topics] page.

### [`kill_pane`][tool-kill-pane]: ending things

[`clear_pane_scrollback`][tool-clear-pane-scrollback],
[`kill_pane`][tool-kill-pane], [`kill_window`][tool-kill-window] and
[`kill_session`][tool-kill-session] sit in the `teardown` toolset:

- The kill tools refuse to end the pane this conversation runs in, or the
  window or session that holds it, unless `confirm_self` is set.
- No tool ends the tmux server itself.

## Build a session

A workspace can begin as the shape tmuxp and [`libtmux-workspace`][workspace]
use:

```yaml
session_name: api-work
windows:
  - window_name: editor
    panes:
      - nvim
  - window_name: services
    layout: even-horizontal
    panes:
      - npm run dev
      - docker compose logs -f
```

The server does not take that document as one call. Creation stays explicit:
create the detached session, keep the ids it returns, then create and split
windows and choose a layout.

### [`create_session`][tool-create-session]: the session first

```json
{"name": "create_session", "arguments": {"session_name": "api-work",
 "window_name": "editor"}}
```

### [`create_window`][tool-create-window] and [`split_window`][tool-split-window]: then its windows and panes

```json
{"name": "create_window", "arguments": {"session_id": "$1",
 "window_name": "services"}}
```

```json
{"name": "split_window", "arguments": {"pane_id": "%2",
 "direction": "right", "percent": 50}}
```

### [`select_layout`][tool-select-layout]: then a layout

```json
{"name": "select_layout", "arguments": {"window_id": "@2",
 "layout": "even-horizontal"}}
```

[`select_layout`][tool-select-layout] takes:

- A layout name or a unique abbreviation such as `even-h`, or an enum spelling
  such as `EVEN_HORIZONTAL`.
- A checksummed layout that tmux returned earlier.

Layout names follow the running tmux version; mirrored main layouts need tmux
3.5. Malformed layout syntax is refused before the window is looked up, and tmux
still decides geometry and pruning.

These tools accept no command or environment payload. Start the process first,
then use [`run_shell_command`][tool-run-shell-command],
[`send_keys`][tool-send-keys] or [`paste_text`][tool-paste-text]. If a later
step fails, the earlier results still say what exists.

## Limit what a client can do

### `LIBTMUX_TOOLSETS`: four independent sets

`inspect`, `manage`, `execute` and `teardown` each grant their own tools, and
none implies another. Ask for the sets a client needs:

```console
$ LIBTMUX_TOOLSETS=inspect,manage libtmux-mcp --socket-name my-project
```

An empty value selects no toolset, and unknown names stop startup.

### `LIBTMUX_TOOLS` and `LIBTMUX_EXCLUDE_TOOLS`: exact names

`LIBTMUX_TOOLS` adds tools by name to what the toolsets grant, and
`LIBTMUX_EXCLUDE_TOOLS` removes tools last:

```console
$ LIBTMUX_TOOLSETS= LIBTMUX_TOOLS=capture_pane,wait_for_text libtmux-mcp \
    --socket-name my-project
```

The same immutable selection governs listing and calls:

- A tool outside it is neither listed nor callable.
- Exclusions win.
- [`call_read_tools_batch`][tool-call-read-tools-batch] keeps its eligible
  nested inspect operations when it is selected alone, unless they are excluded
  explicitly.

### Filtering does not confine effects

- Every call runs with the tmux user's authority.
- Pane input can reach a shell, and reads may return terminal content, process
  environment, or configured commands.
- To contain effects, use a separate OS account, socket permissions, or a
  container.

Every tool carries MCP's four effect hints and a capability row:

- The hints (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
  `openWorldHint`) are the same conservative set on every tool, reads included.
  tmux runs an `after-<command>` hook after every command a tool sends.
- The capability row says what each tool itself does.

### `tmux://capabilities`: what this process can reach

The one resource is static for the process lifetime. It reports the effective
tool surface, its selection provenance, the pinned tmux connection, the
capability row published on every tool, and `minimumTmux`, the oldest tmux the
server accepts (3.3a):

```json
{"method": "resources/read", "params": {"uri": "tmux://capabilities"}}
```

A tmux older than that, or one that reports no release, stops startup with one
`libtmux-mcp: ...` line on stderr and a non-zero exit. The same condition in the
middle of a session, such as a daemon replaced by an older one, returns the
`UNSUPPORTED_FEATURE` error code with the same message.

## The tools

<!-- BEGIN GENERATED TOOL INVENTORY -->
The complete frozen inventory below is generated from the code registry.

| toolset | public tools |
| --- | --- |
| `inspect` | [`list_sessions`](https://libtmux.org/en/java/latest/mcp/tools/list_sessions/) · [`list_windows`](https://libtmux.org/en/java/latest/mcp/tools/list_windows/) · [`list_panes`](https://libtmux.org/en/java/latest/mcp/tools/list_panes/) · [`get_server_info`](https://libtmux.org/en/java/latest/mcp/tools/get_server_info/) · [`get_session_info`](https://libtmux.org/en/java/latest/mcp/tools/get_session_info/) · [`get_window_info`](https://libtmux.org/en/java/latest/mcp/tools/get_window_info/) · [`get_pane_info`](https://libtmux.org/en/java/latest/mcp/tools/get_pane_info/) · [`capture_pane`](https://libtmux.org/en/java/latest/mcp/tools/capture_pane/) · [`capture_since`](https://libtmux.org/en/java/latest/mcp/tools/capture_since/) · [`snapshot_pane`](https://libtmux.org/en/java/latest/mcp/tools/snapshot_pane/) · [`search_panes`](https://libtmux.org/en/java/latest/mcp/tools/search_panes/) · [`find_pane_by_position`](https://libtmux.org/en/java/latest/mcp/tools/find_pane_by_position/) · [`wait_for_text`](https://libtmux.org/en/java/latest/mcp/tools/wait_for_text/) · [`get_tmux_variables`](https://libtmux.org/en/java/latest/mcp/tools/get_tmux_variables/) · [`show_option`](https://libtmux.org/en/java/latest/mcp/tools/show_option/) · [`show_environment`](https://libtmux.org/en/java/latest/mcp/tools/show_environment/) · [`show_hooks`](https://libtmux.org/en/java/latest/mcp/tools/show_hooks/) · [`call_read_tools_batch`](https://libtmux.org/en/java/latest/mcp/tools/call_read_tools_batch/) |
| `manage` | [`rename_session`](https://libtmux.org/en/java/latest/mcp/tools/rename_session/) · [`rename_window`](https://libtmux.org/en/java/latest/mcp/tools/rename_window/) · [`select_window`](https://libtmux.org/en/java/latest/mcp/tools/select_window/) · [`select_pane`](https://libtmux.org/en/java/latest/mcp/tools/select_pane/) · [`select_layout`](https://libtmux.org/en/java/latest/mcp/tools/select_layout/) · [`resize_window`](https://libtmux.org/en/java/latest/mcp/tools/resize_window/) · [`resize_pane`](https://libtmux.org/en/java/latest/mcp/tools/resize_pane/) · [`move_window`](https://libtmux.org/en/java/latest/mcp/tools/move_window/) · [`swap_pane`](https://libtmux.org/en/java/latest/mcp/tools/swap_pane/) · [`set_pane_title`](https://libtmux.org/en/java/latest/mcp/tools/set_pane_title/) · [`wait_for_channel`](https://libtmux.org/en/java/latest/mcp/tools/wait_for_channel/) · [`signal_channel`](https://libtmux.org/en/java/latest/mcp/tools/signal_channel/) · [`set_mouse_enabled`](https://libtmux.org/en/java/latest/mcp/tools/set_mouse_enabled/) · [`set_history_limit`](https://libtmux.org/en/java/latest/mcp/tools/set_history_limit/) |
| `execute` | [`create_session`](https://libtmux.org/en/java/latest/mcp/tools/create_session/) · [`create_window`](https://libtmux.org/en/java/latest/mcp/tools/create_window/) · [`split_window`](https://libtmux.org/en/java/latest/mcp/tools/split_window/) · [`respawn_pane`](https://libtmux.org/en/java/latest/mcp/tools/respawn_pane/) · [`run_shell_command`](https://libtmux.org/en/java/latest/mcp/tools/run_shell_command/) · [`send_keys`](https://libtmux.org/en/java/latest/mcp/tools/send_keys/) · [`send_keys_batch`](https://libtmux.org/en/java/latest/mcp/tools/send_keys_batch/) · [`paste_text`](https://libtmux.org/en/java/latest/mcp/tools/paste_text/) · [`set_synchronize_panes`](https://libtmux.org/en/java/latest/mcp/tools/set_synchronize_panes/) |
| `teardown` | [`clear_pane_scrollback`](https://libtmux.org/en/java/latest/mcp/tools/clear_pane_scrollback/) · [`kill_pane`](https://libtmux.org/en/java/latest/mcp/tools/kill_pane/) · [`kill_window`](https://libtmux.org/en/java/latest/mcp/tools/kill_window/) · [`kill_session`](https://libtmux.org/en/java/latest/mcp/tools/kill_session/) |
<!-- END GENERATED TOOL INVENTORY -->

The same tools by what they do:

- **Name and choose.** [`rename_session`][tool-rename-session] ·
  [`rename_window`][tool-rename-window] · [`select_window`][tool-select-window]
  · [`select_pane`][tool-select-pane] · [`set_pane_title`][tool-set-pane-title]
- **Size and arrange.** [`select_layout`][tool-select-layout] ·
  [`resize_window`][tool-resize-window] · [`resize_pane`][tool-resize-pane] ·
  [`move_window`][tool-move-window] · [`swap_pane`][tool-swap-pane]
- **Create.** [`create_session`][tool-create-session] ·
  [`create_window`][tool-create-window] · [`split_window`][tool-split-window] ·
  [`respawn_pane`][tool-respawn-pane]
- **Type.** [`send_keys`][tool-send-keys] ·
  [`send_keys_batch`][tool-send-keys-batch] · [`paste_text`][tool-paste-text] ·
  [`set_synchronize_panes`][tool-set-synchronize-panes]
- **Configure.** [`set_mouse_enabled`][tool-set-mouse-enabled] ·
  [`set_history_limit`][tool-set-history-limit]
- **End.** [`clear_pane_scrollback`][tool-clear-pane-scrollback] ·
  [`kill_pane`][tool-kill-pane] · [`kill_window`][tool-kill-window] ·
  [`kill_session`][tool-kill-session]

## Settings

| environment | what it chooses |
| --- | --- |
| `LIBTMUX_SOCKET` | one socket name, mutually exclusive with the path |
| `LIBTMUX_SOCKET_PATH` | one absolute socket path |
| `LIBTMUX_TMUX_CONFIG` | one nonempty absolute tmux configuration path |
| `LIBTMUX_TOOLSETS` | any unordered subset of `inspect,manage,execute,teardown` |
| `LIBTMUX_TOOLS` | exact tool names to add |
| `LIBTMUX_EXCLUDE_TOOLS` | exact tool names to remove last |

The surface is frozen before tmux opens. [`--safety`, `LIBTMUX_SAFETY`,
`--watch` and `LIBTMUX_WATCH`][migration] are retired and fail startup.

## Embedding it

The tools are separate from the protocol wiring, because what a tool does to
tmux is worth testing against real tmux and attaching it to a transport is not.
[`TmuxMcpServer`][mcp-server] is the entry point:

<!-- snippet: compile-only: overStdio reads standard input until the client closes it -->
```java
// Given: ServerConfig config
Server server = Server.open(config);

// Serves on stdin and stdout, reading until the client closes the stream.
TmuxMcpServer.overStdio(server);
```

`TmuxMcpServer.serving(server, transport)` takes an MCP transport of your own,
which is how this is tested. It registers the same startup-frozen manifest as
the stdio launcher, and reads its selection from the process environment.

## What it leaves out

- **Server termination.** No tool ends the tmux server itself.
- **Dynamic resources, subscriptions, prompts and live completion.** State
  belongs in typed tools, and `tmux://capabilities` answers what this process
  can reach. [The old routes and prompts][migration] map to typed reads.
- **A generic `set-option`.** [`set_mouse_enabled`][tool-set-mouse-enabled],
  [`set_history_limit`][tool-set-history-limit],
  [`set_synchronize_panes`][tool-set-synchronize-panes] and
  [`set_pane_title`][tool-set-pane-title] cover the supported cases.
- **Open filter expressions.** The Java library's
  [`filter-expr-v1.schema.json`][filter-schema] stays available to application
  code, but a field not in a tool's schema never reaches tmux.
- **Client details and stale channel signals.**
  [`list_sessions`][tool-list-sessions] marks attached sessions, and stale
  signals cannot be drained through MCP.

## Support and status

- **JDK 25 or newer.** It depends on [`libtmux`][libtmux] and the MCP Java SDK.
- **Linux, including WSL.** CI runs on Ubuntu. macOS is supported on a
  best-effort basis, and CI coverage for it is highly limited.
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.
  Tool names and shapes are part of the API.
- **Optional siblings.** [`libtmux-jackson`][jackson] and
  [`libtmux-workspace`][workspace] stay available to Java applications that
  need filter documents or declarative workspace building.
- **Migrating from earlier alpha releases.** [The migration notes][migration]
  list the retired flags, tool names and resources.

## Documentation

- [MCP guide][mcp-guide] — the design, and why each tool is shaped as it is.
- [Tool pages][mcp-tools] — one page for each tool.
- [Filtering guide][filtering] — the expression model behind the wire format.
- [Streaming guide][streaming] — the lower-level control client used by Java
  applications.
- [`libtmux`][libtmux] — the library underneath, and the [root README][root].

[filter-schema]: ../libtmux-jackson/src/main/resources/io/github/libtmux/jackson/filter-expr-v1.schema.json
[filtering]: ../docs/guide/filtering.md
[jackson]: ../libtmux-jackson/README.md
[libtmux]: ../libtmux/README.md
[mcp-guide]: ../docs/guide/mcp.md
[mcp-reference]: https://libtmux.org/en/java/latest/mcp/reference/
[mcp-server]: https://libtmux.org/en/java/latest/mcp/reference/io-github-libtmux-mcp-tmuxmcpserver-tmuxmcpserver/
[mcp-tools]: https://libtmux.org/en/java/latest/mcp/tools/
[mcp-topics]: https://libtmux.org/en/java/latest/mcp/topics/
[migration]: ../MIGRATION.md#libtmux-mcp-toolsets-tool-names-and-resources-replace-the-earlier-surface
[root]: ../README.md
[streaming]: ../docs/guide/streaming.md
[tool-call-read-tools-batch]: https://libtmux.org/en/java/latest/mcp/tools/call_read_tools_batch/
[tool-capture-pane]: https://libtmux.org/en/java/latest/mcp/tools/capture_pane/
[tool-capture-since]: https://libtmux.org/en/java/latest/mcp/tools/capture_since/
[tool-clear-pane-scrollback]: https://libtmux.org/en/java/latest/mcp/tools/clear_pane_scrollback/
[tool-create-session]: https://libtmux.org/en/java/latest/mcp/tools/create_session/
[tool-create-window]: https://libtmux.org/en/java/latest/mcp/tools/create_window/
[tool-find-pane-by-position]: https://libtmux.org/en/java/latest/mcp/tools/find_pane_by_position/
[tool-get-pane-info]: https://libtmux.org/en/java/latest/mcp/tools/get_pane_info/
[tool-get-server-info]: https://libtmux.org/en/java/latest/mcp/tools/get_server_info/
[tool-get-session-info]: https://libtmux.org/en/java/latest/mcp/tools/get_session_info/
[tool-get-tmux-variables]: https://libtmux.org/en/java/latest/mcp/tools/get_tmux_variables/
[tool-get-window-info]: https://libtmux.org/en/java/latest/mcp/tools/get_window_info/
[tool-kill-pane]: https://libtmux.org/en/java/latest/mcp/tools/kill_pane/
[tool-kill-session]: https://libtmux.org/en/java/latest/mcp/tools/kill_session/
[tool-kill-window]: https://libtmux.org/en/java/latest/mcp/tools/kill_window/
[tool-list-panes]: https://libtmux.org/en/java/latest/mcp/tools/list_panes/
[tool-list-sessions]: https://libtmux.org/en/java/latest/mcp/tools/list_sessions/
[tool-list-windows]: https://libtmux.org/en/java/latest/mcp/tools/list_windows/
[tool-move-window]: https://libtmux.org/en/java/latest/mcp/tools/move_window/
[tool-paste-text]: https://libtmux.org/en/java/latest/mcp/tools/paste_text/
[tool-rename-session]: https://libtmux.org/en/java/latest/mcp/tools/rename_session/
[tool-rename-window]: https://libtmux.org/en/java/latest/mcp/tools/rename_window/
[tool-resize-pane]: https://libtmux.org/en/java/latest/mcp/tools/resize_pane/
[tool-resize-window]: https://libtmux.org/en/java/latest/mcp/tools/resize_window/
[tool-respawn-pane]: https://libtmux.org/en/java/latest/mcp/tools/respawn_pane/
[tool-run-shell-command]: https://libtmux.org/en/java/latest/mcp/tools/run_shell_command/
[tool-search-panes]: https://libtmux.org/en/java/latest/mcp/tools/search_panes/
[tool-select-layout]: https://libtmux.org/en/java/latest/mcp/tools/select_layout/
[tool-select-pane]: https://libtmux.org/en/java/latest/mcp/tools/select_pane/
[tool-select-window]: https://libtmux.org/en/java/latest/mcp/tools/select_window/
[tool-send-keys]: https://libtmux.org/en/java/latest/mcp/tools/send_keys/
[tool-send-keys-batch]: https://libtmux.org/en/java/latest/mcp/tools/send_keys_batch/
[tool-set-history-limit]: https://libtmux.org/en/java/latest/mcp/tools/set_history_limit/
[tool-set-mouse-enabled]: https://libtmux.org/en/java/latest/mcp/tools/set_mouse_enabled/
[tool-set-pane-title]: https://libtmux.org/en/java/latest/mcp/tools/set_pane_title/
[tool-set-synchronize-panes]: https://libtmux.org/en/java/latest/mcp/tools/set_synchronize_panes/
[tool-show-environment]: https://libtmux.org/en/java/latest/mcp/tools/show_environment/
[tool-show-hooks]: https://libtmux.org/en/java/latest/mcp/tools/show_hooks/
[tool-show-option]: https://libtmux.org/en/java/latest/mcp/tools/show_option/
[tool-signal-channel]: https://libtmux.org/en/java/latest/mcp/tools/signal_channel/
[tool-snapshot-pane]: https://libtmux.org/en/java/latest/mcp/tools/snapshot_pane/
[tool-split-window]: https://libtmux.org/en/java/latest/mcp/tools/split_window/
[tool-swap-pane]: https://libtmux.org/en/java/latest/mcp/tools/swap_pane/
[tool-wait-for-channel]: https://libtmux.org/en/java/latest/mcp/tools/wait_for_channel/
[tool-wait-for-text]: https://libtmux.org/en/java/latest/mcp/tools/wait_for_text/
[workspace]: ../libtmux-workspace/README.md
