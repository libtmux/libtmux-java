# tmux-workspace

Load and capture tmux workspaces with native Java services. The command names
and flags follow tmuxp 1.74.0, with JSON/NDJSON output across every command.
The CLI requires JDK 21 or newer and targets tmux 3.2a or newer.

This branch is an implementation checkpoint. Native loading, capture,
conversion, imports, discovery, search, editor execution and Python shell
execution are available. Python plugins, custom builders and progress templates
still need implementation and validation.
Native loading rejects unsupported configuration keys before contacting tmux.

## Installation

Build the local distribution from the repository root:

```console
$ ./gradlew :workspace-cli:installDist \
    --max-workers=2 \
    --no-parallel
```

The launcher is `workspace-cli/build/install/tmux-workspace/bin/tmux-workspace`.
Add that directory to `PATH`, or use the full relative launcher path. This
application distribution is separate from the published library artifacts.

## Workspace commands

Save a workspace as `workspace.yaml`:

```yaml
session_name: dev
windows:
  - window_name: editor
    window_index: 0
    layout: even-horizontal
    panes:
      - null
      - shell_command: echo ready
```

Create a directory for the example socket:

```console
$ mkdir -p /tmp/libtmux-java-dev
```

Load it detached on that socket:

```console
$ tmux-workspace load ./workspace.yaml \
    -S /tmp/libtmux-java-dev/workspace-example.sock \
    -d \
    --ndjson
```

Capture a live session as JSON on stdout:

```console
$ tmux-workspace freeze dev \
    -S /tmp/libtmux-java-dev/workspace-example.sock \
    --json
```

Capture recovers topology, directories, focus and configured options. It cannot
recover original command history, bootstrap scripts or plugin intent. Ordinary
loading creates or reuses a session. Append authenticates the inherited tmux
daemon before resolving the current pane; socket aliases are accepted when
they reach that same daemon. Failures report retained changes rather than
claiming rollback.

Explicit window indexes are reserved before implicit windows receive free
indexes from `base-index`. Append rejects existing index collisions before
running scripts or changing options.

`workspace_builder_options.pane_readiness` accepts `auto` (the default),
`always`, `never`, or boolean aliases. Automatic readiness waits for zsh;
`always` also waits for other shells. The check waits up to two seconds for the
pane cursor to move from its origin before sending commands. Cursor movement is
a heuristic, not a guarantee that the shell is ready. A timeout warns and
continues. Blank panes and explicit launch commands never wait or query
the shell policy. A pane's `shell` overrides `window_shell`; `pane_shell` is an
alias. Setting both pane keys is an error.

Pane-level `enter`, `sleep_before` and `sleep_after` set command defaults.
Command mappings can override them; each override carries to later commands in
the same pane. A null sleep resets that delay to zero. `enter: false` types the
command without executing it.

Use `--help` on any command for its arguments. Discovery checks local project
files and the first existing global directory from `TMUXP_CONFIGDIR`, XDG and
the legacy directory. Conversion preserves extension fields and never executes
workspace commands. Explicit file saves protect existing files unless `--force`
is supplied. `--yes` controls confirmation separately.

## Output and Python

`--json` returns one document; `--ndjson` takes precedence when both are present.
Load and child-process output stream as escaped, flushed events. Machine output
never includes ANSI styling or implicit prompts. Interactive editors and Python
shells use the controlling terminal while their result remains on stdout.

Human output uses semantic colors. `NO_COLOR` disables styling, followed by the
explicit `--color` policy. Automatic color also recognizes `FORCE_COLOR`,
`CLICOLOR_FORCE` and `CLICOLOR`.

`--log-level` filters diagnostics from debug through critical, defaulting to
warning. Load accepts `--log-file` for appended JSON log records. New files use
private permissions on POSIX filesystems; existing permissions are preserved.
Info-level logs include both captured bootstrap streams. Diagnostics use stderr
and preserve machine stdout. Invalid log destinations fail before tmux changes.
Runtime append or close failures produce a separate logging diagnostic and
preserve the workspace result and exit status; a failed logger stops recording.

Python shell commands require `TMUX_WORKSPACE_PYTHON` to name an interpreter
with tmuxp 1.74.0 installed. The version is checked before execution. Ordinary
native loading and read commands do not require Python. Search uses Java regex
syntax; it does not launch Python or promise identical Python `re` semantics.

Captured subprocesses retain bounded output and report truncation. Linux uses
an owned `setsid` session to terminate descendants retaining captured output;
other platforms currently use known process handles and need additional
reparenting tests. Successfully detached services with closed output streams
are retained. Full attachment, platform and reference-corpus gates remain open.

## Generated reference and development

Generate metadata from the same graph that parses commands:

```console
$ tmux-workspace --generate schema
```

Generate Bash completion:

```console
$ tmux-workspace --generate bash
```

Run the CLI checks with cached Gradle configuration:

```console
$ ./gradlew :workspace-cli:check \
    --configuration-cache \
    --max-workers=2 \
    --no-parallel
```

The tests use isolated sockets under the Java test root. PTY regressions use
Python 3 standard-library helpers; native application commands do not require
Python. The repository-wide `check` remains the full gate; a focused CLI check
does not replace it.
