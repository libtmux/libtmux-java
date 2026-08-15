# libtmux-mcp

**A tmux server, exposed to a model over the Model Context Protocol.**

Point Claude Desktop, Claude Code, or any MCP client at a tmux socket and it can
list sessions, read what a pane is showing, and run things in one.

`io.github.libtmux:libtmux-mcp` — not yet on Maven Central.

> **Alpha.** Tool names and shapes will change without notice.

## Run it

```console
$ ./gradlew :libtmux-mcp:installDist
```

That writes a launcher at `libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp`.
An MCP client starts it as a subprocess and speaks JSON-RPC over its stdin and
stdout.

### Claude Desktop / Claude Code

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

The launcher takes `--socket <path>`, `--socket-name <name>`, and `--tmux <binary>`.
Set `LIBTMUX_MODE=control` in its environment to reuse one tmux client instead of
starting a process per command.

## Tools

| tool | takes | gives back |
| --- | --- | --- |
| `tmux_list_sessions` | — | every session, with the names of its windows |
| `tmux_list_panes` | optional `filter` | panes, with the `%id` other tools take as a target |
| `tmux_capture_pane` | `pane_id` | what the pane is currently showing, one entry per line |
| `tmux_run` | `pane_id`, `command` | runs it in that pane, as though typed |
| `tmux_new_window` | `session`, `name` | the new window's first pane id |

**Every tool addresses a pane by id, never by position.** A model works from a
listing it read some time ago, and pane indexes move as neighbours come and go, so
a positional target would quietly act on the wrong pane.

**A failure comes back as a tool error, not an exception.** A model can act on
`no pane %9` — by listing panes again — and can do nothing with a stack trace.

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
which is how this is tested.

Use `TmuxTools` directly if you want the behaviour without the protocol:

```java
TmuxTools tools = new TmuxTools(server);

List<PaneSummary> running = tools.describe(
        server.panes().stream().filter(Pane_.command().startsWith("nvim")).toList());
```

## Install

```kotlin
dependencies {
    implementation(platform("io.github.libtmux:libtmux-bom:0.0.1-alpha.1"))
    implementation("io.github.libtmux:libtmux-mcp")
}
```

Depends on [`libtmux`](../libtmux/) and [`libtmux-jackson`](../libtmux-jackson/).

## Next

- [Filtering guide](../docs/guide/filtering.md) — the expression model behind the wire format
- [`libtmux`](../libtmux/) — the library underneath
- [Root README](../README.md)
