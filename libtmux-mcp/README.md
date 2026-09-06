# libtmux-mcp

**A tmux server, exposed to a model over the Model Context Protocol.**

Point Claude Code, Claude Desktop, Codex, or any MCP client at a tmux socket and
it can find its way around, read what a pane is showing, run a command and wait
for it, and build a whole session with typed operations.

`io.github.libtmux:libtmux-mcp` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-mcp).

> **Alpha.** Tool names and shapes will change without notice.

## Run it

```console
$ ./gradlew :libtmux-mcp:installDist
```

That writes a launcher at `libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp`.
An MCP client starts it as a subprocess and speaks JSON-RPC over its stdin and
stdout.

Commands below that use `libtmux-mcp` assume that launcher's `bin` directory is
on `PATH`; otherwise substitute the full path.

| flag | what it chooses |
| --- | --- |
| `--socket <path>` | which tmux server, by socket path |
| `--socket-name <name>` | which tmux server, by name under tmux's own directory |
| `--tmux <binary>` | which tmux to run |

Without a socket flag, the launcher pins the named socket `libtmux-mcp`. When
that socket does not exist, it starts tmux with the package's minimal
configuration and enables all four toolsets. A process that finds an existing
or explicitly selected server cannot prove how it was configured, so teardown
is omitted from its default surface.

| environment | what it chooses |
| --- | --- |
| `LIBTMUX_SOCKET` | one socket name, mutually exclusive with the path |
| `LIBTMUX_SOCKET_PATH` | one absolute socket path |
| `LIBTMUX_TMUX_CONFIG` | one nonempty absolute tmux configuration path |
| `LIBTMUX_TOOLSETS` | any unordered subset of `inspect,manage,execute,teardown` |
| `LIBTMUX_TOOLS` | exact tool names to add |
| `LIBTMUX_EXCLUDE_TOOLS` | exact tool names to remove last |

The surface is frozen before tmux opens. An empty `LIBTMUX_TOOLSETS` value
selects no toolset; unknown names and empty comma-separated elements stop
startup. Existing launcher configurations must migrate:

- `--safety` and `LIBTMUX_SAFETY` are retired and fail startup. The old
  `readonly`, `mutating`, and `destructive` values map to `inspect`;
  `inspect,manage,execute`; and all four toolsets, respectively. Use
  `LIBTMUX_TOOLS` and `LIBTMUX_EXCLUDE_TOOLS` for exact exceptions.
- `--watch` and `LIBTMUX_WATCH` are retired and fail startup. MCP no longer
  sends dynamic resource notifications. Use `wait_for_text`,
  `wait_for_channel`, or `capture_since`; Java applications can use
  `ControlClient`.

See [Watching, instead of polling](#watching-instead-of-polling) for bounded
waits and [Safety](#safety) for the capability and trust boundary.

### Claude Code

```console
$ claude mcp add tmux -- /absolute/path/to/libtmux-mcp --socket /tmp/my-app/s
```

### Codex CLI

```console
$ codex mcp add tmux -- /absolute/path/to/libtmux-mcp --socket /tmp/my-app/s
```

### Claude Desktop

Add to `claude_desktop_config.json`:

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

### Any other client

It speaks JSON-RPC over stdin and stdout, so anything implementing MCP's stdio
transport can launch it. There is nothing to configure but the path and the flags.

## What it feels like

> **You:** What's running in my tmux panes, and is the test suite still going?
>
> **Agent:** Three panes. `%0` is a shell, `%1` is running `pytest`, `%2` is a
> shell in the `logs` window. Reading `%1` now — it is on `tests/test_auth.py`,
> 84 passed so far, nothing failed yet. Want me to wait for it to finish?

The agent reads and drives the terminal directly. No pasting output back and
forth, no switching windows to check on something long-running.

## The three rules the whole surface is built on

**Target by id, never by position.** A model works from a listing it read some
turns ago, and indexes move as neighbours come and go. `%1` is a pane, `@1` a
window, `$1` a session. A bare `1` is refused, because tmux would read it as an
index and act on a real but unintended pane.

**Wait, do not poll.** Every wait is a tool. An agent that sends a command and
then reads the pane repeatedly to guess whether it finished spends a call per
look and still cannot tell a finished command from a stalled one.

**Reads are bounded and say so.** Every read is capped, keeps the newest lines,
and reports what it dropped. An answer silently shortened reads as a complete
one, which is how a model concludes a build printed nothing.

## Tools

<!-- BEGIN GENERATED TOOL INVENTORY -->
The complete frozen inventory below is generated from the code registry.

| toolset | public tools |
| --- | --- |
| `inspect` | `list_sessions` · `list_windows` · `list_panes` · `get_server_info` · `get_session_info` · `get_window_info` · `get_pane_info` · `capture_pane` · `capture_since` · `snapshot_pane` · `search_panes` · `find_pane_by_position` · `wait_for_text` · `get_tmux_variables` · `show_option` · `show_environment` · `show_hooks` · `call_read_tools_batch` |
| `manage` | `rename_session` · `rename_window` · `select_window` · `select_pane` · `select_layout` · `resize_window` · `resize_pane` · `move_window` · `swap_pane` · `set_pane_title` · `wait_for_channel` · `signal_channel` · `set_mouse_enabled` · `set_history_limit` |
| `execute` | `create_session` · `create_window` · `split_window` · `respawn_pane` · `run_shell_command` · `send_keys` · `send_keys_batch` · `paste_text` · `set_synchronize_panes` |
| `teardown` | `clear_pane_scrollback` · `kill_pane` · `kill_window` · `kill_session` |
<!-- END GENERATED TOOL INVENTORY -->

Existing callers from earlier alpha releases must also migrate tool names:

- `tmux_capture_pane`, `tmux_capture_since`, `tmux_list_panes`,
  `tmux_list_sessions`, `tmux_list_windows`, `tmux_paste_text`,
  `tmux_resize_pane`, `tmux_search_panes`, `tmux_select_layout`,
  `tmux_send_keys`, `tmux_show_environment`, `tmux_show_hooks`,
  `tmux_signal_channel`, `tmux_wait_for_channel`, and `tmux_wait_for_text`
  retain their suffix without `tmux_`. `list_panes` no longer accepts a
  filter; filter its bounded metadata client-side. Use `search_panes` only for
  displayed text.
- `tmux_run`, `tmux_new_session`, `tmux_new_window`, `tmux_split_pane`, and
  `tmux_show_options` become `run_shell_command`, `create_session`,
  `create_window`, `split_window`, and `show_option`, in the same order.
- `tmux_whoami` splits into `get_server_info` and the caller marker from
  `list_panes`. `tmux_rename` becomes `rename_session` or `rename_window`;
  `tmux_select` becomes `select_window` or `select_pane`; `tmux_kill` becomes
  `kill_session`, `kill_window`, or `kill_pane`. Server termination is not
  exposed.
- `tmux_set_option` has no generic equivalent. Migrate supported uses to
  `set_mouse_enabled`, `set_history_limit`, `set_synchronize_panes`, or
  `set_pane_title`.
- `tmux_apply_workspace` becomes explicit `create_session`, `create_window`,
  `split_window`, and `select_layout` calls followed by `run_shell_command`,
  `send_keys`, or `paste_text`.
- `tmux_list_servers`, `tmux_list_clients`, and `tmux_drain_channel` have no
  direct equivalents. Each process pins one server, described by
  `get_server_info`; `list_sessions` marks attached sessions but exposes no
  client details; stale channel signals cannot be drained through MCP.

### Finding your way

| tool | gives back |
| --- | --- |
| `get_server_info` | the pinned server's identity, version, and current state |
| `list_sessions` | sessions, with stable `$id` values |
| `list_windows` | windows, with the `@id` other tools take |
| `list_panes` | panes, with the `%id` other tools take |
| `get_session_info`, `get_window_info`, `get_pane_info` | one target's metadata |
| `find_pane_by_position` | one pane at a named window corner |

### Reading what panes show

| tool | gives back |
| --- | --- |
| `capture_pane` | what a pane shows now, plus a cursor |
| `capture_since` | **only what is new** since a cursor, plus the next cursor — finished lines only, so half a line is never handed over as though it were the whole of one |
| `snapshot_pane` | bounded content and pane metadata together |
| `search_panes` | which panes show bounded plain text or a bounded RE2 pattern |
| `show_environment`, `show_hooks`, `show_option` | selected configuration state |
| `get_tmux_variables` | a capped set of validated variable names |
| `call_read_tools_batch` | up to sixteen typed inspect calls with full nested MCP results when they fit |

`list_panes` reads metadata — what is *running*, and where. `search_panes`
reads content — what is *displayed*. "Which pane mentions the error" is a search.

Copy mode is an attached-client interface, not a prerequisite for reading pane
text. Set `history: true` on `capture_pane` or `snapshot_pane` for bounded
scrollback, use `search_panes` to locate displayed text, and continue from a
cursor with `capture_since` instead of entering or cancelling a person's mode.

Key sends resolve the target's current effective synchronized cohort and refuse
the whole send when one configured recipient is modal, dead, the caller pane,
or displayed by a terminal client. Paste applies the same guard to its target
only, while framed shell runs require one guarded effective recipient at both
preflights. Each preflight reads pane and client state in one snapshot; it is
still an observation rather than a delivery receipt. `resolved_pane_ids`
reports configured membership rather than confirmed recipients or delivery.

Batch rows retain the nested MCP envelope rather than flattening its text or
structured content. The complete JSON-RPC response, including line framing, is
capped at 1,000,000 bytes. A row that would cross that boundary remains in
order with `result: null` and
`resultTruncated: true`; the outer result sets `truncated` and reports the
removed byte count in `truncatedBytes`.

A serialized request ID may use at most 524,288 bytes; a larger ID returns an
`id: null` invalid-request error before any tool runs.

### Waiting

| tool | for |
| --- | --- |
| `run_shell_command` | **a command you wrote** — sends it, waits, returns output *and exit status* in one call |
| `wait_for_text` | output you did not start: a dev server, a daemon, someone else's build |
| `wait_for_channel` | anything you can compose `; tmux wait-for -S name` into |
| `signal_channel` | the other end of that |

### Input, structure, configuration

`rename_session` · `rename_window` · `select_window` · `select_pane` ·
`select_layout` · `resize_window` · `resize_pane` · `move_window` · `swap_pane` ·
`set_pane_title` · `set_mouse_enabled` · `set_history_limit` · `create_session` ·
`create_window` · `split_window` ·
`respawn_pane` · `send_keys` · `send_keys_batch` · `paste_text` ·
`set_synchronize_panes`

### Ending things

`clear_pane_scrollback` · `kill_pane` · `kill_window` · `kill_session`. The kill
tools refuse to end the pane this conversation is running through, or one of its
containers, unless `confirm_self` is set. No tool ends the tmux server itself.

## Waiting, which is the part that pays for itself

An agent driving a terminal spends most of its time waiting, and MCP gives it no
sleep primitive — so a wait that is not a tool becomes a polling loop in the
agent's turn, where it has no ceiling at all.

**You wrote the command.** One call, and the answer is a number rather than an
inference:

```json
{"name": "run_shell_command",
 "arguments": {"pane_id": "%1", "command": "pytest -q", "timeout": 120}}
```

```json
{"outcome": "SIGNALLED", "exit_status": 1, "output": ["...", "1 failed, 84 passed"]}
```

`outcome` is `SIGNALLED` when the command finished, `TIMED_OUT` when it was still
running at the deadline, and `SERVER_GONE` when tmux itself died underneath the
wait. Those mean different things, and only the first makes `exit_status`
meaningful — tmux reports a server that died under a waiter as a *successful*
wake, so "it worked" is never the answer on its own.

Completion runs inside the pane's trusted POSIX shell: an inherited inner
subshell contains the authored command, while an outer exit trap emits its status
and signals through one absolute tmux client and the server's resolved `-S`
socket. Ordinary output aliases and functions are tolerated; pre-existing
functions named `trap`, `eval`, `exit`, or exactly like that resolved client are
outside this boundary. Marker `display-message` calls honor the selected trusted
server's command aliases and hooks.
ASCII control characters and DEL are refused in executable and socket routes
before those values can enter framing.

**You did not write it.** Always pass `stop`:

```json
{"name": "wait_for_text",
 "arguments": {"pane_id": "%2", "patterns": ["Listening on"],
               "stop": ["error:", "EADDRINUSE"], "timeout": 60}}
```

Without `stop`, a run that fails in the first second is still waited on until the
deadline, and what comes back is a timeout instead of the error. Patterns are
plain text unless you pass `regex` — a model asking for `[FAILED]` means those
eight characters, not a character class.

Only output arriving *after* the call counts, so text already on the screen from
an hour ago cannot satisfy a wait for something that has not happened yet.

Every wait is capped (30 s by default, 2 minutes hard) and reports the ceiling it
actually enforced. The cap protects the agent's turn, not the connection: a tool
call that blocks does not stop this server answering anything else.

A client's request deadline is separate. The Java SDK 2.0.1 client defaults to
20 seconds, so configure it above any longer wait you request. With that SDK,
cancelling or timing out abandons the answer but does not stop the synchronous
handler or undo tmux changes it already dispatched.

## Watching, instead of polling

The MCP surface no longer keeps a hidden control client or advertises dynamic
resource subscriptions. Instead it gives an agent three bounded ways to wait
without rereading a screen in a loop.

- `wait_for_text` watches one pane for wanted or stop patterns and returns the
  output that arrived during the call.
- `wait_for_channel` lets tmux itself block until a cooperating command signals
  a channel.
- `capture_since` takes an opaque cursor and returns only finished lines added
  since that point.

```json
{"name": "capture_since",
 "arguments": {"pane_id": "%1", "cursor": "<cursor from capture_pane>"}}
```

The Java library still exposes control-mode subscriptions directly when an
application genuinely needs a long-lived event stream; the
[streaming guide](../docs/guide/streaming.md) covers that lower-level API.

## Safety

Four unordered toolsets replace the old safety ceiling: `inspect`, `manage`,
`execute`, and `teardown`. They are capabilities, not increasing levels. Ask for
the independent sets a client needs, then add or exclude exact tool names.

```console
$ LIBTMUX_TOOLSETS=inspect,manage libtmux-mcp --socket-name my-project
```

```console
$ LIBTMUX_TOOLSETS= LIBTMUX_TOOLS=capture_pane,wait_for_text libtmux-mcp \
    --socket-name my-project
```

The same immutable selection governs both listing and calls. A hidden tool is
not callable, exclusions win, and an aggregate-only
`call_read_tools_batch` retains its eligible nested inspect operations unless
they are excluded explicitly.

Filtering the catalog does not confine effects. Every call runs with the tmux
user's authority; pane input can reach a shell, and reads may return terminal
content, process environment, or configured commands. Use a separate OS
account, socket permissions, or a container when effects must be contained.

Every tool carries MCP's own effect hints — `readOnlyHint`, `destructiveHint`,
`idempotentHint`, and `openWorldHint` — plus its full native capability row.
Those claims remain conservative when the selected server's configuration is
unknown.

## Resources, prompts, completion

There is one resource: `tmux://capabilities`. It is static for the process
lifetime and reports the effective tool surface, its selection provenance, the
pinned tmux connection, and the same capability row published on every tool.

```json
{"method": "resources/read", "params": {"uri": "tmux://capabilities"}}
```

Earlier dynamic resource routes migrate to typed reads:

- `tmux://server` becomes `get_server_info`; the static capability resource
  adds connection and selection provenance.
- `tmux://sessions` and `tmux://panes` become `list_sessions` and
  `list_panes`.
- `tmux://sessions/{session_name}` and `tmux://panes/{pane_id}` become
  `get_session_info` and `get_pane_info`.
- `tmux://panes/{pane_id}/content` becomes `capture_pane`, `snapshot_pane`, or
  `capture_since`. Use `wait_for_text` when the old subscription was waiting
  for a terminal condition.

The removed prompts remain useful as explicit tool workflows:

- `run_and_wait` becomes one `run_shell_command` call.
- `watch_until_ready` uses `wait_for_text`, or `snapshot_pane` followed by
  `capture_since` when output must be carried across turns. After a timeout,
  continue from the returned cursor instead of restarting the observation.
- `find_the_pane` composes `list_panes`, `search_panes`,
  `find_pane_by_position`, and `get_pane_info` as needed.
- `build_workspace` composes the create, split, layout, title, selection, and
  execution tools documented below.
- `clean_up_safely` starts with `list_panes` to identify the MCP pane and
  `list_sessions` to identify attached sessions, then uses the specific pane,
  window, or session teardown tool. An attached session may have a person
  watching it, so apparently abandoned state may still be live.

Earlier live `completion/complete` suggestions for `pane_id` have no direct
replacement. Call `list_panes`, then pass the exact id through the typed tool
schema.

There are deliberately no dynamic hierarchy or pane-content resources,
templates, subscriptions, prompts, or live completion routes. State belongs in
typed tools, while the resource answers the one question a client should not
have to infer: what this frozen process can reach and disclose.

## Filtering, which is the interesting part

A server with forty panes gives a model forty things to reason about. MCP now
does two narrower kinds of filtering: startup selection removes tools the client
does not need, and `search_panes` narrows terminal content without returning
every pane capture.

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

One call examines at most 200 panes, 20,000 lines, 1,000,000 UTF-8 bytes, and
five seconds of matching work. The answer says when a pane, line, byte, time, or
result limit stopped it. Pattern count and UTF-8 size are rejected before tmux
opens; regular expressions use the bounded RE2 dialect.

For several different observations, batch exact inspect calls instead of asking
for one broad untyped projection:

```json
{"name": "call_read_tools_batch", "arguments": {"operations": [
  {"tool": "list_panes", "arguments": {}},
  {"tool": "show_option", "arguments": {"scope": "server", "name": "status"}}
]}}
```

The Java library's richer query API and its versioned
[`filter-expr-v1.schema.json`](../libtmux-jackson/src/main/resources/io/github/libtmux/jackson/filter-expr-v1.schema.json)
remain available to application code. MCP does not accept that open expression
document: its authoritative schemas expose only the bounded inputs above, and a
field not in those schemas never reaches tmux.

## A whole session from one description

A workspace may still begin as the same readable shape tmuxp and
[`libtmux-workspace`](../libtmux-workspace/) use:

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

The MCP server no longer accepts that whole document as one opaque mutation.
Creation stays explicit and typed: create the detached session, retain the IDs
it returns, then create and split windows and choose a layout.

```json
{"name": "create_session", "arguments": {"session_name": "api-work",
 "window_name": "editor"}}
```

```json
{"name": "create_window", "arguments": {"session_id": "$1",
 "window_name": "services"}}
```

```json
{"name": "split_window", "arguments": {"pane_id": "%2",
 "direction": "right", "percent": 50}}
```

```json
{"name": "select_layout", "arguments": {"window_id": "@2",
 "layout": "even-horizontal"}}
```

These tools accept no command or environment payload. Start the configured
process first, then use `run_shell_command`, `send_keys`, or `paste_text` for
workload input. If a later step fails, the earlier typed results still identify
exactly what exists and what can be removed.

## Embedding it

The tools are separate from the protocol wiring, because what a tool does to tmux
is worth testing against real tmux and attaching it to a transport is not.

<!-- snippet: compile-only: overStdio reads standard input until the client closes it -->
```java
Server server = Server.open(config);

// Serves on stdin and stdout, reading until the client closes the stream.
TmuxMcpServer.overStdio(server);
```

`TmuxMcpServer.serving(server, transport)` takes an MCP transport of your own,
which is how this is tested. It registers the same startup-frozen manifest and
single static capability resource as the stdio launcher.

The removed `serving(server, ceiling, transport)` and watching-boolean overloads
become `serving(server, transport)`. It reads selection from the process
environment; Java applications own `ControlClient` subscriptions directly.

## Install

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.7"))
    implementation("io.github.libtmux:libtmux-mcp")
}
```

Depends on [`libtmux`](../libtmux/) and the MCP Java SDK. The sibling
[`libtmux-jackson`](../libtmux-jackson/) and
[`libtmux-workspace`](../libtmux-workspace/) modules remain available to Java
applications that need filter documents or declarative workspace building.

## Next

- [MCP guide](../docs/guide/mcp.md) — the design, and why each tool is shaped as it is
- [Filtering guide](../docs/guide/filtering.md) — the expression model behind the wire format
- [Streaming guide](../docs/guide/streaming.md) — the lower-level control client
  used by Java applications
- [`libtmux`](../libtmux/) — the library underneath
- [Root README](../README.md)
