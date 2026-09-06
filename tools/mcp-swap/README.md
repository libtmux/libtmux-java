# mcp-swap

`mcp-swap` points installed agent CLIs at the `libtmux-mcp` server built from
this checkout. It is a private Gradle application: the repository tests and
distributes it locally, but no published libtmux artifact contains it.

Use it to exercise a branch in real clients, then restore every config byte to
its pre-swap state.

## Build the utility

From the repository root:

```console
$ ./gradlew :tools:mcp-swap:installDist
```

The launcher is
`tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap`. Rebuild it after changing
the utility.

## Inspect clients

Show which of the eight known clients have both their executable on `PATH` and
their config file present:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap detect
```

Show the current `tmux` entry in each existing config:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap status
```

For Claude, unscoped status checks both the top-level user entry and this
repository's project entry. `--scope user` or `--scope project` limits that
view. `--name` selects another server entry. Repeat `--cli` to limit a command;
comma-separated names also work. `antigravity` is an alias for `agy`.

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap status \
    --name libtmux \
    --cli claude,codex
```

## Select a server build

The default `dist` source builds `:libtmux-mcp:installDist`, then records its
launcher directly. No Gradle process sits in front of the MCP handshake.

Preview the complete transaction without building, writing, or creating state
directories:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap use \
    --dry-run
```

Point every existing config at the built server and a tmux socket:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap use \
    --socket /tmp/libtmux-java-dev/demo/s
```

Use `gradle` when every client launch should rebuild current sources. This is
convenient while editing, but a cold Gradle start can exceed a client's MCP
handshake deadline.

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap use \
    --source gradle \
    --socket-name demo
```

Use `path` for an executable built elsewhere:

```console
$ ./gradlew :libtmux-mcp:installDist
```

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap use \
    --source path \
    --bin libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp
```

`--socket` and `--socket-name` are mutually exclusive. Without either, the MCP
server uses its normal tmux endpoint resolution. `--tmux` can name a different
tmux executable.

Before taking the transaction lock or changing a client config, `use` starts
each final client-specific command, sends MCP `initialize`, and requires a
complete JSON-RPC 2.0 result. It accepts a server that stays running after the
handshake, then terminates the process tree. Use `--no-preflight` only when the
server cannot be safely started outside the client.

## Set the capability surface

The swapper preserves an entry's existing environment and accepts repeatable
overrides. Use the capability model's toolsets and exact include/exclude lists:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap use \
    --env LIBTMUX_TOOLSETS=inspect,execute \
    --env LIBTMUX_EXCLUDE_TOOLS=send_keys
```

`LIBTMUX_SAFETY` is retired. The swap preserves an inherited value in the
preflight command so the server can report the migration error instead of
silently widening authority. Supply `LIBTMUX_TOOLSETS` explicitly; the
successful transaction then removes the retired setting and keeps all
unrelated environment values. Passing `LIBTMUX_SAFETY` explicitly is rejected.

## Restore configs

Restore every selected config from its first pre-swap backup:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap revert
```

Claude defaults to project scope, under
`projects[<absolute repository>].mcpServers`. Use `--scope user` for its
top-level `mcpServers` fallback. The two scopes have independent recovery
records and can coexist; an unscoped revert restores both in strict LIFO order.
To unwind only the newest layer:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap revert \
    --cli claude \
    --scope user
```

The scope flag is silently coerced to `user` for every non-Claude client.

A repeated `use` keeps the first backup. It updates the owned config and
recovery record only after proving that the config still matches the previous
swap. `revert` likewise refuses a human edit, route change, replaced backup, or
incomplete recovery pair.

Run read-only diagnostics before a swap:

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap doctor
```

## Client files

| Client | Config | Server table |
| --- | --- | --- |
| Claude | `~/.claude.json` (user or current-project scope) | `mcpServers` |
| Codex | `~/.codex/config.toml` | `mcp_servers` |
| Cursor | `~/.cursor/mcp.json` | `mcpServers` |
| Gemini | `~/.gemini/settings.json` | `mcpServers` |
| Grok | `~/.grok/config.toml` | `mcp_servers` |
| agy / Antigravity | `~/.gemini/config/mcp_config.json` | `mcpServers` |
| OpenCode | `$XDG_CONFIG_HOME/opencode/opencode.jsonc` | `mcp` |
| Pi adapter | `~/.pi/agent/mcp.json` | `mcpServers` |

Claude's project scope is the one project-aware exception. The utility does not
walk repository or workspace config files for other clients. OpenCode's
`.jsonc` file wins over its sibling global JSON files, so the swap owns that
layer while preserving comments and trailing commas. Pi has no built-in MCP
client; its entry takes effect only when the third-party `pi-mcp-adapter` is
installed. `detect` and `doctor` report that caveat.

## Transaction contract

One command covers all selected clients as a single transaction:

- It serializes with every language port through
  `$XDG_STATE_HOME/libtmux-mcp-dev/swap/state.lock`; Java backup and state
  filenames carry a `mcp-swap-java` marker so another port cannot claim them.
- It parses every config and validates all config, backup, state, and lock
  routes before the first replacement.
- It preflights the exact environment-adjusted command for every selected
  client and aborts if any final command changes before the lock is acquired.
- It rejects aliases and hard links across selected and unselected clients.
- It writes private, checksummed recovery records and binds the first backup's
  file identity, bytes, mode, and route.
- It gives Claude's user and project layers distinct Java recovery files and
  refuses to remove an older layer before a newer layer on the same config.
- It rechecks every protected route at each publication boundary.
- It rolls a failed commit back in reverse order. If exact rollback becomes
  uncertain, it preserves the human destination and the remaining recovery
  artifacts instead of guessing.
- It keeps config symlinks themselves intact while authenticating their target
  and refusing a retarget.

JSON, JSONC, and TOML updates keep unrelated servers and values. JSONC and TOML
updates also keep comments; swap and revert restore the complete original byte
sequence.

The test suite exercises all eight clients, every ordering of their selectors,
Claude scope layering and LIFO restoration, bounded MCP initialization and
process-tree cleanup, repeat swaps, byte-exact restoration, malformed recovery
data, path aliases, late destination changes, lock contention, and interrupted
transaction rollback.

## Test the utility

```console
$ ./gradlew :tools:mcp-swap:check
```

The module uses the repository's JDK 21 toolchain and is included in the root
build, but it is absent from Maven publication and the BOM.
