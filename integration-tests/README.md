# integration-tests

**The library's real-tmux suite. Not published.**

244 tests that start actual tmux servers and drive them. They live outside every
published module on purpose: a suite inside one artifact's test source set makes
that artifact's dependencies and lifecycle answerable for how the whole library
is tested.

```console
$ ./gradlew :integration-tests:test
```

Against every supported tmux release:

```console
$ ./scripts/tmux-matrix.sh ~/tmux-builds
```

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=~/tmux-builds
```

Under a different carrier, which must not change any answer:

```console
$ LIBTMUX_MODE=control ./gradlew :integration-tests:test
```

## The one that matters most

[`ExecutionModeConformanceTest`](src/test/java/io/github/libtmux/it/ExecutionModeConformanceTest.java)
runs the same trajectory under every carrier and compares step by step. It is
where the library's central promise is kept, and it has caught a real breach:
[`docs/spikes/21`](../docs/spikes/21-command-group-boundaries.md).

## Before blaming a change

This host may carry hundreds of tmux servers belonging to sibling ports. Count
first:

```console
$ ./scripts/reap-stale-servers.sh
```

[`CONTRIBUTING.md`](../.github/CONTRIBUTING.md) explains why their debris
becomes this suite's intermittent failures, and why only servers under this
port's own roots are ever reaped.

## Next

- [Testing with real tmux](../docs/guide/testing.md) · [`libtmux-junit5`](../libtmux-junit5/)
