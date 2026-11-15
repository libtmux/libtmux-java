# build-tmux-matrix

Builds every tmux release the matrix tests into a tree that
`./gradlew testTmuxMatrix` can use. A maintainer runs it before a release, or
to run the whole matrix on one machine instead of one release per CI runner.

```console
$ uv run scripts/build-tmux-matrix/build_tmux_matrix.py ~/tmux-builds
```

It reads the supported releases and the releases below the floor from
`gradle/tmux-lanes.json`, so it cannot drift from what the matrix runs. For each
one without a `bin/tmux` under the destination, it downloads the release's
source archive from [tmux/tmux](https://github.com/tmux/tmux/releases), runs
`./configure --prefix=<destination>/<release>`, `make` and `make install`, and
prints the version of each result. A release that is already built is kept.

The previews, `master` and the development builds, are not built here. CI builds
them from a commit, and `gh workflow run tmux-matrix.yml` runs any ref
(see [CONTRIBUTING](../../.github/CONTRIBUTING.md#running-ci-against-any-tmux)).

Building needs a C compiler, `make`, `libevent` and `ncurses` headers, and
`yacc` or `bison`. Then run the matrix:

```console
$ ./gradlew testTmuxMatrix -PlibtmuxMatrix=~/tmux-builds
```

The tests stand in a small source archive for tmux, so they run offline:

```console
$ uv run pytest scripts/build-tmux-matrix
```
