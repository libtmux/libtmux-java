# scripts

**What the build does not do.**

| script | does |
| --- | --- |
| [`tmux-matrix.sh`](tmux-matrix.sh) | builds every supported tmux release into a tree the version matrix can use |
| [`reap-stale-servers.sh`](reap-stale-servers.sh) | reports and optionally ends tmux servers this port abandoned |
| [`mcp_swap.py`](mcp_swap.py) | points every installed agent CLI at this build of `libtmux-mcp` |

## Build the tmux matrix

```console
$ ./scripts/tmux-matrix.sh ~/tmux-builds
```

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=~/tmux-builds
```

It reads the lane list out of `build-logic`, so it cannot drift from the releases
the matrix actually runs. CI builds the same set, one release per runner.

## Reap abandoned servers

```console
$ ./scripts/reap-stale-servers.sh          # report only
```

```console
$ ./scripts/reap-stale-servers.sh --reap   # end them
```

The suite already does this for itself. This is for the case it cannot reach: a
server whose test JVM was killed and whose socket the system's temp cleaner has
since removed, which is then addressable only by its own argv.

**It only ever touches sockets under this port's roots** — `/tmp/libtmux-java-test`
and `/tmp/libtmux-java-dev`. Sibling ports' servers are counted and reported, never
killed. [`CONTRIBUTING.md`](../.github/CONTRIBUTING.md) explains why.

## Try the MCP server in a real agent

See what each CLI points at now:

```console
$ uv run scripts/mcp_swap.py status
```

Point them all at this build, having first said what it would do:

```console
$ uv run scripts/mcp_swap.py use --dry-run
```

```console
$ uv run scripts/mcp_swap.py use \
    --socket /tmp/libtmux-java-dev/demo/s
```

The swapper records the executable and connection selector. Set
`LIBTMUX_TOOLSETS`, `LIBTMUX_TOOLS`, and `LIBTMUX_EXCLUDE_TOOLS` in the MCP
client's launch environment when the default capability surface is not the one
you want.

The retired `--safety` and `--watch` swapper arguments are rejected; the
[module README](../libtmux-mcp/README.md#run-it) maps their replacements.

The default covers Claude, Codex, Cursor, Gemini, Grok, `agy`, OpenCode, and
Pi. Repeat `--cli` to limit a command; `antigravity` is accepted as an alias
for the canonical `agy` name. OpenCode uses its global `opencode.jsonc` file.
Pi uses the `pi-mcp-adapter` config because Pi has no built-in MCP client;
`detect` and `doctor` report when that adapter is absent.

Put them back:

```console
$ uv run scripts/mcp_swap.py revert
```

It rewrites **global** configs only, touches only the one server entry named by
`--name` (default `tmux`), and keeps everything else in the file — including
comments and trailing commas in JSONC, and comments in TOML. A backup and its
private recovery record are taken together once. Repeat swaps and reverts first
verify the exact config, path topology, backup, record, and server route; a
mismatch leaves the recovery pair intact. All selected files commit as one
transaction; failed commits reverse in order and retain recovery files if exact
rollback is not possible. `--dry-run` validates the complete plan without
building or writing.

To try it without changing anything at all, most CLIs take a config per
invocation instead — `claude --mcp-config <file> --strict-mcp-config`, or
`codex exec -c 'mcp_servers.tmux.command="..."'`.

## Next

- [CONTRIBUTING](../.github/CONTRIBUTING.md) · [Root README](../README.md)
