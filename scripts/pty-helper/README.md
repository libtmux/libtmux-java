# pty-helper

`pty_helper.py` is the pseudo-terminal helper the Java tests drive terminal
programs with. `drain_pty` reads what a pty master has ready without waiting,
and `wait_pty` waits for a child to exit while draining its pty, which a child
that fills the pty buffer needs before it can exit.

It is test data, not a command. The `processTestResources` task of
`integration-tests` and `libtmux-workspace-cli` copies it to
`/io/github/libtmux/fixtures/pty_helper.py` on the test classpath.
`NativeClient` puts its text ahead of the Python program it runs, so its imports
are the ones that program relies on and it defines only the two functions the
program calls. `ProcessTest` copies it beside the driver programs it runs,
which are checked Python files under
`libtmux-workspace-cli/src/test/resources/io/github/libtmux/workspace/cli/probes`
and import it. CI formats, lints and type checks those files with the scripts.

```console
$ uv run pytest scripts/pty-helper
```
