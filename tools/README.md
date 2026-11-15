# tools

**Developer tooling: what the build does not do. Not published.**

| tool | does |
| --- | --- |
| [`mcp-swap/`](mcp-swap/README.md) | points every installed agent CLI at this build of `libtmux-mcp`, and back |

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
