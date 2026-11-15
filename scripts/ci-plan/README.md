# ci-plan

Plans the jobs of `.github/workflows/tmux-matrix.yml` from the tmux releases this
project tests, and checks the results of those jobs.

`gradle/tmux-lanes.json` is the one list of releases. The Gradle plugin
`libtmux.tmux-matrix` registers a test task for each entry, and this script turns
the same file into the workflow's matrix:

| key | holds |
| --- | --- |
| `lanes` | the supported range, oldest first |
| `previewLanes` | releases and refs that run ahead of the range and may fail |
| `rejectionLanes` | releases below the floor, which must refuse every call |
| `clientLane` | the supported release that talks to the rejection lanes' daemons |

The `plan` job runs it and the `required` job checks what the lanes reported:

```console
$ uv run scripts/ci-plan/ci_plan.py matrix \
    --event push
```

A push or pull request plans the oldest and newest of `lanes`. The weekly
schedule, a tag and `--scope full` plan everything, and `--scope custom` plans
the releases, tags, branches and commits named by `--tmux-ref`, repeated for
each JDK in `--jdk`. `--tmate` opens a debugging session on a dispatch with
exactly one lane. The script prints the plan and, with `--github-output`,
writes it as step outputs. It refuses a run CI would not make before a runner is
spent on it.

```console
$ uv run scripts/ci-plan/ci_plan.py verify \
    --needs '{"lanes": {"result": "success"}}'
```

`verify` exits nonzero for any job that did not succeed.

Run the tests, which also check that every rule fails when it is removed:

```console
$ uv run pytest scripts/ci-plan
```
