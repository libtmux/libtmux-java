# scripts

**What the build does not do.**

| script | does |
| --- | --- |
| [`tmux-matrix.sh`](tmux-matrix.sh) | builds every supported tmux release into a tree the version matrix can use |
| [`reap-stale-servers.sh`](reap-stale-servers.sh) | reports and optionally ends tmux servers this port abandoned |

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

## Next

- [CONTRIBUTING](../CONTRIBUTING.md) · [Root README](../README.md)
