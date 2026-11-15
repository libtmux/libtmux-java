# pty-helper

`pty_helper.py` is the pseudo-terminal helper the Java tests drive terminal
programs with. `drain_pty` reads what a pty master has ready without waiting,
and `wait_pty` waits for a child to exit while draining its pty, which a child
that fills the pty buffer needs before it can exit.

It is test data, not a command. The `processTestResources` task of
`integration-tests` and `libtmux-workspace-cli` copies it to
`/io/github/libtmux/fixtures/pty_helper.py` on the test classpath, and
`NativeClient` and `ProcessTest` put its text ahead of the Python program they
run. So its imports are the ones those programs rely on, and it defines only the
two functions they call.

```console
$ uv run pytest scripts/pty-helper
```
