# check-tmux-build

Checks that the tmux a lane built is the one the lane is named after. The shared
`build-tmux` action can restore a cached build, and a lane that silently runs
another release proves nothing about the one it names. `tmux-matrix.yml` builds
each release under `~/tmux-builds/<slug>` and runs this before the suite:

```console
$ uv run scripts/check-tmux-build/check_tmux_build.py --slug 3.8 --ref 3.8 --verify release
```

`--verify release` requires `tmux <ref>` exactly, `development` requires a
`tmux next-X.Y` build, and `none` only prints what the build reports. `--client`
names a second build that must be the release it says, such as the 3.3a client
that reaches the daemons of the releases below the floor. A build that is not what
its lane names prints a `::error::` annotation and the exit status is 1.

Run the tests with the [script runner](../README.md#running-the-checks).
