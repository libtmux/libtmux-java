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
killed. [`AGENTS.md`](../AGENTS.md) explains why.

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
    --socket /tmp/libtmux-java-dev/demo/s \
    --safety destructive \
    --watch
```

Put them back:

```console
$ uv run scripts/mcp_swap.py revert
```

It rewrites **global** configs only, touches only the one server entry named by
`--name` (default `tmux`), and keeps everything else in the file — including
comments in TOML. The backup is taken once, so swapping something already swapped
still reverts to the config that was there before any of it started.

To try it without changing anything at all, most CLIs take a config per
invocation instead — `claude --mcp-config <file> --strict-mcp-config`, or
`codex exec -c 'mcp_servers.tmux.command="..."'`.

## Next

- [CONTRIBUTING](../CONTRIBUTING.md) · [Root README](../README.md)
