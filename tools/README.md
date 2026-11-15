# tools

**Developer tooling: what the build does not do. Not published.**

| tool | does |
| --- | --- |
| [`tmux-matrix.sh`](tmux-matrix.sh) | builds every supported tmux release into a tree the version matrix can use |
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
