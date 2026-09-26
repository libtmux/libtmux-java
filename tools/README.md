# tools

**Developer tooling: what the build does not do. Not published.**

| tool | does |
| --- | --- |
| [`tmux-matrix.sh`](tmux-matrix.sh) | builds every supported tmux release into a tree the version matrix can use |
| [`reap-stale-servers.sh`](reap-stale-servers.sh) | reports and optionally ends tmux servers this port abandoned |
| [`mcp-swap/`](mcp-swap/README.md) | points every installed agent CLI at this build of `libtmux-mcp`, and back |

## Build the tmux matrix

```console
$ ./tools/tmux-matrix.sh ~/tmux-builds
```

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=~/tmux-builds
```

It reads the lane list out of `build-logic`, so it cannot drift from the releases
the matrix actually runs. CI builds the same set, one release per runner.

## Reap abandoned servers

```console
$ ./tools/reap-stale-servers.sh          # report only
```

```console
$ ./tools/reap-stale-servers.sh --reap   # end them
```

The suite already does this for itself. This is for the case it cannot reach: a
server whose test JVM was killed and whose socket the system's temp cleaner has
since removed, which is then addressable only by its own argv.

**It only ever touches sockets under this port's roots** — `/tmp/libtmux-java-test`
and `/tmp/libtmux-java-dev`. Servers under the other `/tmp/libtmux-*` roots, which
other libtmux ports use, are counted and reported, never killed. [`CONTRIBUTING.md`](../.github/CONTRIBUTING.md) explains why.

## Try the MCP server in a real agent

```console
$ ./gradlew :tools:mcp-swap:installDist
```

```console
$ tools/mcp-swap/build/install/mcp-swap/bin/mcp-swap use --dry-run
```

[`mcp-swap/README.md`](mcp-swap/README.md) covers the clients it knows, source
modes, capability overrides, and how `revert` restores every config byte.

## Next

- [CONTRIBUTING](../.github/CONTRIBUTING.md) · [Root README](../README.md)
