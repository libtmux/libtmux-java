# tmux-workspace

Load and capture tmux workspaces with native Java services. The command names
and flags follow tmuxp 1.74.0, with JSON/NDJSON output across every command.
The CLI requires JDK 21 or newer and targets tmux 3.2a or newer.

`load -2` forces 256-color support for native tmux clients, including attachment.
Without it, tmux detects terminal capabilities. The legacy `-8` flag fails before
workspace lookup because supported tmux versions removed 88-color mode.

This branch is an implementation checkpoint. Native loading, capture,
conversion, imports, discovery, search, editor execution and Python shell
execution are available. Python plugins and custom builders use an explicit
tmuxp bridge; their effects remain outside the native builder's guarantees.
Native loading rejects unsupported configuration keys before contacting tmux.
YAML anchors and merge keys expand into ordinary workspace values. Date-like
scalars remain text. Documents must contain one mapping; duplicate keys,
cyclic aliases, nonfinite numbers and nonstring mapping keys are rejected.
Files ending in `.json` require JSON syntax. YAML expansion is limited to
100 levels and 100,000 values.

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
daemon before resolving the current pane, then keeps that session across input
files even if a script moves the pane. Socket aliases are accepted when
they reach that same daemon. Failures report retained changes rather than
claiming rollback.

Explicit window indexes are reserved before implicit windows receive free
indexes from `base-index`. Append reserves indexes from later input files and
rejects collisions across all remaining inputs before running scripts or
changing options. Temporary-window removal preserves
requested indexes when `renumber-windows` is enabled, then restores its local
or inherited setting. Restoration failures appear in the partial result.

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

Terminal stderr displays event-driven load progress. `--progress-format` accepts
the `default`, `minimal`, `window`, `pane` and `verbose` presets, or a template.
`TMUXP_PROGRESS_FORMAT` supplies its default. Templates accept these fields:

- Identity: `session`, `workspace_path`, `window`.
- Position: `window_index`, `window_total`, `window_progress`, `pane_index`,
  `pane_total`, `pane_progress`, `progress`.
- Completion: `windows_done`, `windows_remaining`, `window_progress_rel`,
  `pane_done`, `pane_remaining`, `pane_progress_rel`, `session_pane_total`,
  `session_panes_done`, `session_panes_remaining`, `session_pane_progress`,
  `overall_percent`, `summary`.
- Bars: `bar`, `pane_bar`, `window_bar`. `status_icon` is empty.

Unknown fields stay literal; `{{` and `}}` escape braces. The script panel shows
the latest output while preserving its original stdout/stderr destination.
`--progress-lines` or `TMUXP_PROGRESS_LINES` sets its rows: 3 by default, 0 hides
the panel, and -1 uses the available initial terminal height. Retained panel
text is bounded to 65,536 characters. Terminal dimensions are sampled once;
the display does not track resizing, and Unicode clipping is conservative.
`--no-progress`, `TMUXP_PROGRESS=0`, `TERM=dumb`, redirected stderr and machine
output disable the display. Completion and interruption clear its frame.

`--log-level` filters diagnostics from debug through critical, defaulting to
warning. Load accepts `--log-file` for appended JSON log records. New files use
private permissions on POSIX filesystems; existing permissions are preserved.
Info-level logs include both captured bootstrap streams. Diagnostics use stderr
and preserve machine stdout. Invalid log destinations fail before tmux changes.
Runtime append or close failures produce a separate logging diagnostic and
preserve the workspace result and exit status; a failed logger stops recording.

Interrupting a command returns exit 130. Writes retain their order within each
stream. Final output has a bounded delivery allowance after interruption;
an undrained pipe can leave that output incomplete. Caller-supplied output
streams remain open. If a stream ignores interruption, its pending write can
finish later when the caller drains it; cancellation does not reap that write.

Python shell commands and extension workspaces use `TMUX_WORKSPACE_PYTHON`
(default `python3`) with tmuxp 1.74.0 installed. The version is checked before
any input workspace changes. Nonempty `plugins`, `workspace_builder`, or
`workspace_builder_paths` select the bridge. Builder paths resolve relative to
the workspace file; custom builders receive their own configuration keys.
Plugin import or version failures stop the load instead of prompting to skip
the plugin. Ordinary native loading and read commands do not require Python.
This CLI also permits an explicit custom builder to omit `windows`. Common
fields still receive tmuxp expansion, and present windows receive inherited
defaults. The builder supplies its own topology when it omits that field.
Search uses Java regex syntax; it does not launch Python or promise identical
Python `re` semantics.

Extension append receives the originally authenticated session ID, even if an
earlier input moves the invoking pane. Combining extension append with
`before_script` fails before Python or tmux starts: the pinned Python builder
can delete a borrowed session when that script fails. Native scripted append
and extension append without a script remain available.

Bridge results identify `engine: python`, `effects_scope: observed`, and
`effects_unknown: true`. The bridge keeps `owned_session: false`: observing a
session does not establish ownership. Reported window and pane IDs were absent
from the selected daemon's pre-child snapshot and appear in the target session
after the child exits or is interrupted. They do not
describe arbitrary plugin actions, transient objects, or changes to other
sessions. Child output remains separate from native build events; the bridge
does not produce native pane progress. Java retains observed sessions after
extension failure. Python builders and plugins remain executable user code
with their own mutation and cleanup behavior.

On tmux 3.2a, the Python classic builder can fail after tmux rewrites a session
name containing `$`. Use a name without `$` for extension workspaces on that
version; the bridge does not alter tmux's naming behavior.

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

For repeated CLI execution while editing, keep Gradle watching the sources:

```console
$ ./gradlew :workspace-cli:runDevelopment \
    --args='--help' \
    --continuous \
    --configuration-cache \
    --max-workers=2 \
    --no-parallel \
    -Dorg.gradle.continuous.quietperiod=50
```

Replace `--help` with the command to exercise. This task compiles separate
development classes with javac and uses the JVM's first compilation tier for
startup. It skips Error Prone and NullAway for the CLI classes and does not run
tests. Published libraries, normal compilation, installation and `check` keep
their full checks and never consume these development classes. The initial
build includes setup; later edits reuse Gradle and the compiler. This JVM
setting also limits later optimization in long-running commands. Use normal
`run` or installation tasks for representative runtime benchmarks.

Run the CLI checks with cached Gradle configuration:

```console
$ ./gradlew :workspace-cli:check \
    --configuration-cache \
    --max-workers=2 \
    --no-parallel
```

The tests use isolated sockets under the Java test root. PTY regressions use
Python 3 standard-library helpers; native application commands do not require
Python. Set `TMUX_WORKSPACE_TEST_PYTHON` to an interpreter with tmuxp 1.74.0 to
enable the extension fixtures. Tests replace `HOME`; user-installed Python
packages also need an explicit `PYTHONUSERBASE`. The repository-wide `check`
remains the full gate; a focused CLI check does not replace it.
