# ci-check

Checks that the docs and the workflows agree with `gradle/tmux-lanes.json`, the
one list of supported tmux releases. The `plan` job of
`.github/workflows/tmux-matrix.yml` runs it on every push and pull request.

```console
$ uv run scripts/ci-check/ci_check.py
```

It prints each disagreement to standard error and exits nonzero when there is
one:

- `README.md` and `.github/CONTRIBUTING.md` state a tmux range other than the
  first and last `lanes`, or do not say what a push tests;
- `CONTRIBUTING.md` has no `gh workflow run tmux-matrix.yml` command, or
  `RELEASING.md` does not say a release runs the whole matrix;
- `tmux-matrix.yml` lists tmux releases of its own, splices a dispatch input
  into a script, has a tmate step that is not pinned, limited to the actor and
  timed out, has an aggregate job that forgot a job, or a concurrency group that
  lets dispatches cancel each other;
- `release.yml` does not run the whole table before it publishes;
- a published module has no `CHANGELOG.md` with its `## Unreleased` section, its
  README does not link it, or the publication convention does not put it in the
  jar;
- a workflow calls `apt-get` directly instead of through the shared `apt-get`
  action of `libtmux/.github`.

`--root` points the check at another checkout. The tests copy the files it
reads, break one thing at a time, and expect the guard for that thing to say so:

```console
$ uv run pytest scripts/ci-check
```
