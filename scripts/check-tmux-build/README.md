# check-tmux-build

Checks that the tmux a lane runs is the one the lane is named after. The shared
`build-tmux` action validates a restored build before it uses it. This runs after
the action and checks the wiring around it: the build under
`~/tmux-builds/<slug>` is the release the matrix row names, a development lane
runs a `next-X.Y` build, and the client build is the release it says.
`tmux-matrix.yml` runs it before the suite:

```console
$ uv run scripts/check-tmux-build/check_tmux_build.py --slug 3.8 --ref 3.8 --verify release
```

`--verify release` requires `tmux <ref>` exactly, `development` requires a
`tmux next-X.Y` build, and `none` only prints what the build reports. `--client`
names a second build that must be the release it says, such as the 3.3a client
that reaches the daemons of the releases below the floor. A build that is not what
its lane names prints a `::error::` annotation and the exit status is 1.

`tmux -V` stops after 10 s (`bounded.PROBE`), and a build that cannot report its
version prints a `::error::` annotation.

Run the tests with the [script runner](../README.md#running-the-checks).
