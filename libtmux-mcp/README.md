# libtmux-mcp

**A tmux server, exposed to a model over the Model Context Protocol.**

Point Claude Code, Claude Desktop, Codex, or any MCP client at a tmux socket and
it can find its way around, read what a pane is showing, run a command and wait
for it, and build a whole session from one description.

`io.github.libtmux:libtmux-mcp` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-mcp).

> **Alpha.** Tool names and shapes will change without notice.

## Run it

```console
$ ./gradlew :libtmux-mcp:installDist
```

That writes a launcher at `libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp`.
An MCP client starts it as a subprocess and speaks JSON-RPC over its stdin and
stdout.

| flag | what it chooses |
| --- | --- |
| `--socket <path>` | which tmux server, by socket path |
| `--socket-name <name>` | which tmux server, by name under tmux's own directory |
| `--tmux <binary>` | which tmux to run |
| `--safety readonly\|mutating\|destructive` | how much the model may do — see [Safety](#safety) |
| `--watch` | push notifications as tmux changes — see [Watching](#watching-instead-of-polling) |

`LIBTMUX_SAFETY` and `LIBTMUX_WATCH` set the last two for an operator who cannot
edit the client's launch command. `LIBTMUX_MODE=control` reuses one tmux client
instead of starting a process per command.

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
      "args": ["--socket", "/tmp/my-app/s", "--safety", "mutating"]
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

### Finding your way

| tool | gives back |
| --- | --- |
| `tmux_whoami` | which server this is, and **which pane this conversation is coming through** |
| `tmux_list_servers` | every tmux server this user has, by socket |
| `tmux_list_sessions` | sessions, with the windows in each |
| `tmux_list_windows` | windows, with the `@id` other tools take |
| `tmux_list_panes` | panes, with the `%id` other tools take — optionally narrowed by a `filter` |
| `tmux_list_clients` | who is attached, so you know whether a person is watching |

### Reading what panes show

| tool | gives back |
| --- | --- |
| `tmux_capture_pane` | what a pane shows now, plus a cursor |
| `tmux_capture_since` | **only what is new** since a cursor, plus the next cursor — finished lines only, so half a line is never handed over as though it were the whole of one |
| `tmux_search_panes` | which panes are showing some text |

`tmux_list_panes` reads metadata — what is *running*, and where. `tmux_search_panes`
reads content — what is *displayed*. "Which pane mentions the error" is a search.

### Waiting

| tool | for |
| --- | --- |
| `tmux_run` | **a command you wrote** — sends it, waits, returns output *and exit status* in one call |
| `tmux_wait_for_text` | output you did not start: a dev server, a daemon, someone else's build |
| `tmux_wait_for_channel` | anything you can compose `; tmux wait-for -S name` into |
| `tmux_signal_channel`, `tmux_drain_channel` | the other end of that |

### Input, structure, configuration

`tmux_send_keys` · `tmux_paste_text` · `tmux_new_session` · `tmux_new_window` ·
`tmux_split_pane` · `tmux_apply_workspace` · `tmux_rename` · `tmux_select` ·
`tmux_select_layout` · `tmux_resize_pane` · `tmux_show_options` ·
`tmux_set_option` · `tmux_show_hooks` · `tmux_show_environment`

### Ending things

`tmux_kill` ends a pane, window, session, or the whole server. Offered only at
the `destructive` ceiling, and it **refuses to end the pane this conversation is
running through** unless `confirm_self` is set.

## Waiting, which is the part that pays for itself

An agent driving a terminal spends most of its time waiting, and MCP gives it no
sleep primitive — so a wait that is not a tool becomes a polling loop in the
agent's turn, where it has no ceiling at all.

**You wrote the command.** One call, and the answer is a number rather than an
inference:

```json
{"name": "tmux_run",
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

**You did not write it.** Always pass `stop`:

```json
{"name": "tmux_wait_for_text",
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

## Watching, instead of polling

With `--watch`, this server attaches a tmux control client and asks tmux to
report a format whenever its value changes. tmux does the comparing itself, about
once a second, and sends nothing while nothing changes — so a client subscribed
to a pane spends nothing at all while it is idle.

What arrives is `notifications/resources/updated` naming the resource that went
stale: `tmux://panes/%1/content` when that pane produces output, `tmux://sessions`
and `tmux://panes` when a window appears, closes, or is renamed.

It is off by default because it is not free: watching means attaching a client,
and an attached client is a real change to the server. The one attached here is
hidden from `tmux_list_clients`, so it cannot be mistaken for a person.

## Safety

Three tiers, the same three every port of libtmux uses.

```java
Safety.READONLY.allows(Safety.MUTATING);      // → false
Safety.MUTATING.allows(Safety.READONLY);      // → true
Safety.MUTATING.allows(Safety.DESTRUCTIVE);   // → false
Safety.ofWireName("destructive");             // → DESTRUCTIVE
```

A tool above the ceiling is **not listed at all**, rather than listed and
refused. A model cannot be tempted by a tool it never saw, and an error it can do
nothing about is wasted context. The server's instructions say plainly what is
missing and why, so a model does not spend a turn looking for it.

Every tool also carries MCP's own annotations — `readOnlyHint`, `destructiveHint`,
`idempotentHint` — derived from its tier rather than stated per tool, so a tool
that kills a session cannot describe itself as read-only by forgetting to.

## Resources, prompts, completion

**Resources** are the same state, addressable rather than asked for. A client can
attach one to a conversation and refresh it without spending a tool call or a
model's decision.

`tmux://server` · `tmux://sessions` · `tmux://panes` ·
`tmux://sessions/{session_name}` · `tmux://panes/{pane_id}` ·
`tmux://panes/{pane_id}/content`

**Prompts** are worked recipes for the jobs that take several tools in an order
that matters: `run_and_wait`, `watch_until_ready`, `find_the_pane`,
`build_workspace`, `clean_up_safely`.

**Completion** is answered live. A client asking what could go in `{pane_id}`
gets the pane ids that exist right now, not a fixed list and not a round trip
through `tmux_list_panes`.

## Filtering, which is the interesting part

A server with forty panes gives a model forty things to reason about.
`tmux_list_panes` takes an optional `filter`: the same versioned document every
port of libtmux reads.

```json
{
  "filter": {
    "schema": "libtmux.filter/1",
    "model": "pane",
    "expr": {
      "node": "compare",
      "field": "pane_current_command",
      "op": "starts_with",
      "value": "nvim"
    }
  }
}
```

Field and operator names are **tmux's own format names** — `pane_current_command`,
not anything Java calls a field — so a model that has seen the schema once can
write one for any libtmux port. Combine them with `and`, `or`, `not`:

```json
{"node": "and", "operands": [
  {"node": "compare", "field": "pane_active", "op": "equals", "value": true},
  {"node": "compare", "field": "pane_current_command", "op": "starts_with", "value": "nvim"}
]}
```

Schema: [`filter-expr-v1.schema.json`](../libtmux-jackson/src/main/resources/io/github/libtmux/jackson/filter-expr-v1.schema.json).
A malformed document comes back as a tool error naming what was wrong.

**One capture either way.** The filter runs over what the single read already
returned, so a narrower answer costs no more tmux commands than the whole listing.

## A whole session from one description

`tmux_apply_workspace` takes the shape tmuxp uses, so a file somebody already has
is one a model can send:

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

One call instead of a dozen. A call cannot half-succeed, and a layout tmux would
refuse is refused while the description is still text — before any session exists
to leave half-built.

## Embedding it

The tools are separate from the protocol wiring, because what a tool does to tmux
is worth testing against real tmux and attaching it to a transport is not.

<!-- snippet: compile-only: overStdio reads standard input until the client closes it -->
```java
Server server = Server.open(config);

// Serves on stdin and stdout, reading until the client closes the stream.
TmuxMcpServer.overStdio(server);
```

`TmuxMcpServer.serving(server, ceiling, transport)` takes an MCP transport of
your own, which is how this is tested. Add a `boolean watching` argument to have
it attach a control client and push notifications as tmux changes.

## Install

<!-- snippet: skip: build configuration, not library code -->
```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.5"))
    implementation("io.github.libtmux:libtmux-mcp")
}
```

Depends on [`libtmux`](../libtmux/), [`libtmux-jackson`](../libtmux-jackson/) and
[`libtmux-workspace`](../libtmux-workspace/).

## Next

- [MCP guide](../docs/guide/mcp.md) — the design, and why each tool is shaped as it is
- [Filtering guide](../docs/guide/filtering.md) — the expression model behind the wire format
- [`libtmux`](../libtmux/) — the library underneath
- [Root README](../README.md)
