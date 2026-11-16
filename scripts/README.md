# Scripts

Support scripts for CI, releases and maintainer tasks.
[SCRIPTS.md](../.github/SCRIPTS.md) says what may stay here. Each is typed
Python in a directory of its own with a README and tests, and is run through
`uv`.

| Directory | Job |
| --- | --- |
| [`build-tmux-matrix/`](build-tmux-matrix/README.md) | Build every tmux release the matrix tests into one tree |
| [`check-tmux-build/`](check-tmux-build/README.md) | Check that a lane's tmux is the release it is named after |
| [`ci-check/`](ci-check/README.md) | Check that the docs and workflows agree with `gradle/tmux-lanes.json` |
| [`ci-plan/`](ci-plan/README.md) | Plan the jobs of the tmux matrix workflow and check their results |
| [`lib/`](lib/README.md) | `bounded`: run a command under a time limit that also stops what it started |
| [`no-tmux-survivors/`](no-tmux-survivors/README.md) | Fail a job when a tmux server outlived its suite |
| [`pty-helper/`](pty-helper/README.md) | Read from and wait on a child on a pseudo-terminal; test data the Java tests import |
| [`reap-stale-servers/`](reap-stale-servers/README.md) | List, and end, the tmux servers this port abandoned |
| [`release-checks/`](release-checks/README.md) | Decide the release version, require passing CI, and carry the signing passphrase |
| [`verify-staged-release/`](verify-staged-release/README.md) | Check a staged release against what the Central Portal refuses |

The format, lint, type and test commands for all of them, and for the Python test
programs under `libtmux-workspace-cli/src/test/resources`,
`integration-tests/src/test/resources` and `tools/mcp-swap/src/test/resources`,
are in
[CONTRIBUTING](../.github/CONTRIBUTING.md#checking-the-scripts).
