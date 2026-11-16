# no-tmux-survivors

Fails a CI job when a tmux server outlived the suite it ran. A real-tmux suite
that leaves servers running would be invisible in CI and expensive on a
developer's machine, and the runner is discarded either way, so the end of the
job is the one place to ask for free. `ci.yml`, `tmux-matrix.yml` and
`clojure-compatibility.yml` run it after their suites, whatever the suites
reported:

```console
$ uv run scripts/no-tmux-survivors/no_tmux_survivors.py
```

It finds processes with `pgrep tmux` and lists each one's command line as a
`::error::` annotation. With none it prints `no tmux server survived the suite`
and exits 0; with any it exits 1. It uses `pgrep` and `ps`, which Linux and
macOS share.

Run the tests with the [script runner](../README.md#running-the-checks).
