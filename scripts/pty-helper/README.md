# pty-helper

`pty_helper.py` is the pseudo-terminal helper the Java tests drive terminal
programs with. `drain_pty` reads what a pty master has ready without waiting,
and `wait_pty` waits for a child to exit while draining its pty, which a child
that fills the pty buffer needs before it can exit.

It is test data, not a command. The `processTestResources` task of
`integration-tests` and `libtmux-workspace-cli` copies it to
`/io/github/libtmux/fixtures/pty_helper.py` on the test classpath. `ProcessTest`
and `NativeClient` copy it beside the Python programs they run, which import it.
Those are checked Python files: the probes under
`libtmux-workspace-cli/src/test/resources/io/github/libtmux/workspace/cli/probes`,
and `ordinary_client.py`, the driver that gives `NativeClient`'s tmux client a
terminal, under `integration-tests/src/test/resources/io/github/libtmux/it`. CI
formats, lints and type checks those files with the scripts.

```console
$ uv run pytest scripts/pty-helper
```
