# lib

Modules the scripts share. A script imports one after putting `scripts/lib` on
`sys.path`; the tests find it through `pythonpath` in `pyproject.toml`.

## bounded

Runs external commands under a time limit. When the limit passes, or the script
is interrupted, it kills the command's whole process group, so a compiler below
`make` cannot outlive the limit. `subprocess.run(timeout=)` kills only the
direct child. A test fails when a script starts a process without it.

| Name | What it does |
| --- | --- |
| `run(args, timeout=...)` | Like `subprocess.run`, in a new process group. |
| `stop_group(pid)` | Kills the group a bounded command leads. |
| `PROBE` | 10 s, for a command that only reads local state. |

Measured on this machine with `hyperfine -N --warmup 3`, `PROBE` sits above
`tmux -V` (1.5 ms), `gpg --batch --verify` of a detached signature (4.1 ms),
`pgrep tmux` (23.2 ms) and `ps -o command= -p 1` (40.1 ms) by two orders of
magnitude or more. `brew --prefix` is not measured: Homebrew is not installed
here. `gh run list --limit 50` took 2.5 s once, so the 60 s `NETWORK_SECONDS` in
`release-checks` is one API request with room for a slow one.

Run the tests, which start a command that starts a child and check that both die:

```console
$ uv run --locked --offline pytest scripts/lib
```
