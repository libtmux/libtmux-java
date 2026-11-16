# no-tmux-survivors

Fails a CI job when a tmux server outlived the suite it ran. A real-tmux suite
that leaves servers running goes unnoticed in CI, because the runner is discarded
after the job, and costs memory on a developer's machine. `ci.yml`,
`tmux-matrix.yml` and `clojure-compatibility.yml` run it after their suites,
whatever the suites reported:

```console
$ uv run scripts/no-tmux-survivors/no_tmux_survivors.py
```

It finds processes with `pgrep tmux` and lists each one's command line as a
`::error::` annotation. With none it prints `no tmux server survived the suite`
and exits 0; with any it exits 1. It uses `pgrep` and `ps`, which Linux and
macOS share.

`pgrep` and `ps` each stop after 10 s (`bounded.PROBE`); a hang prints
`::error::cannot look for tmux servers` and exits 1 instead of holding the job.

Run the tests with the [script runner](../README.md#running-the-checks).
