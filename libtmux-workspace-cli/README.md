# libtmux-workspace-cli

**`tmux-workspace` loads, captures, converts and imports tmux workspaces.**

Command names and flags follow [tmuxp](https://tmuxp.git-pull.com/) 1.74.0, and
every command answers in JSON or NDJSON on request. Loading, capture,
conversion, imports, discovery and search run on the JVM alone; only the
Python shell, tmuxp plugins and custom builders need Python. JDK 25 or newer, tmux 3.2a or newer.

`io.github.libtmux:libtmux-workspace-cli` — [on Maven Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace-cli).

> **Alpha.** Commands, flags and output records will change without notice.

## Install

Build the launcher from the repository root:

```console
$ ./gradlew :libtmux-workspace-cli:installDist
```

That writes `libtmux-workspace-cli/build/install/tmux-workspace/bin/tmux-workspace`.
Put its directory on `PATH`.

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

Writing the capture to a file requires `--save-to`. `freeze` derives no
destination from the session, because a session name is tmux's to choose and
carries neither a directory nor an extension.

Capture recovers topology, directories, focus and configured options, preserving
carriage returns and embedded or trailing line feeds in option values. Capture
currently omits session-local environment variables and cannot recover original
command history, bootstrap scripts or plugin intent. Normal loading expands
defined environment variables in captured strings.

Loading resolves `~` and defined `$VAR` references against the environment the
CLI runs in, and it does so in every value tmuxp does: names, directories,
scripts, option and environment values, and pane commands. An undefined
variable is left as written, so the pane's own shell still sees it. Mapping keys
are never expanded.

Ordinary loading creates or reuses a session. Append authenticates the inherited tmux
daemon before resolving the current pane, then keeps that session across input
files even if a script moves the pane. Socket aliases are accepted when
they reach that same daemon. Failures report retained changes rather than
claiming rollback.

All input layouts are checked before scripts run or sessions change. Named
layouts accept unique abbreviations; version-sensitive names use the running
daemon, or the selected client when no daemon is listening. Classic saved
layouts require a valid checksum, consistent geometry, a nonempty tree, enough
pane cells and at most 256 nested splits. This rejects malformed geometry even
when tmux could repair or prune it. JSON layouts require tmux 3.8 (including its
release candidate), valid cell structure and enough pane cells, with at most
200 nested objects. tmux can still reject a layout during application.
A window naming no `layout` tiles its panes; tmuxp instead stacks them,
halving each split. That is a deliberate difference from tmuxp. A window naming
no `focus` keeps the first window active, which tmuxp does not. A pane naming
no `focus` leaves the last pane of its window active, which is what tmuxp
leaves: panes are created detached here and selected afterwards, rather than
each split taking the cursor with it.

Explicit window indexes are reserved before implicit windows receive free
indexes from `base-index`. Append reserves indexes from later input files and
rejects collisions across all remaining inputs before running scripts or
changing options. Temporary-window removal preserves
requested indexes when `renumber-windows` is enabled, then restores its local
or inherited setting. Restoration failures appear in the partial result.

`workspace_builder_options.pane_readiness` accepts `auto` (the default),
`always`, `never`, or boolean aliases. `auto` and `always` both wait, whatever
the pane's shell is, because the problem is that a shell owns its terminal only
once it has drawn: a command sent before that is echoed by the tty and then
redrawn by the line editor, so the pane shows it twice. The check waits up to
two seconds for the pane cursor to move from its origin before sending
commands. Cursor movement is a heuristic, not a guarantee that the shell is
ready. A timeout warns and continues. Blank panes and explicit launch commands
never wait. An unrecognised key under `workspace_builder_options` is a warning,
not a refusal. A pane's `shell` overrides `window_shell`; `pane_shell` is an
alias. Setting both pane keys is an error.

Pane-level `enter`, `sleep_before` and `sleep_after` set command defaults.
Command mappings can override them; each override carries to later commands in
the same pane. A null sleep resets that delay to zero. `enter: false` types the
command without executing it.

Use `--help` on any command for its arguments. Discovery checks local project
files and the first existing global directory from `TMUXP_CONFIGDIR`, XDG and
the legacy directory. Conversion preserves extension fields and never executes
workspace commands. Explicit file saves protect existing files unless `--force`
is supplied. `convert` asks for confirmation before writing unless `--yes` is
given; `freeze --save-to` names its own destination and needs neither `--yes`
nor a terminal to write it.

### Documents

Native loading rejects a key it does not implement before contacting tmux. A
key starting with `x-`, at any level, is the exception: a vendor extension,
accepted, ignored, and preserved by `convert`. YAML anchors and merge keys
expand into ordinary values, and date-like scalars stay text. A document holds
one mapping; duplicate keys, cyclic aliases, nonfinite numbers and nonstring
mapping keys are rejected. A file ending in `.json` must be JSON. YAML expansion
stops at 100 levels and 100,000 values.

`load -2` forces 256-color support for tmux clients, attachment included.
Without it, tmux detects the terminal's capabilities. `-8` fails before
workspace lookup: supported tmux releases removed 88-color mode.

## Imports

`import tmuxinator` and `import teamocil` translate supported source shapes and
validate the resulting native workspace before writing stdout or a destination.
`convert` remains lossless document conversion and does not validate loading.

Tmuxinator window command arrays stay sequential commands in one pane; explicit
`panes` create separate panes. Project `pre_window` arrays form one `; `-joined
command, and window `pre` arrays form one ` && `-joined command before each
explicit pane. `pre_tab` is accepted as an alias for `pre_window`.
Teamocil `commands` arrays form one `; `-joined command; legacy `cmd` and `splits`
are accepted. Window options and the first requested window/pane focus are
preserved; absent focus selects the first item. Layouts and source order remain
part of the translated workspace.

Imports record an absolute project directory from the invocation directory,
including when `root` is omitted. Window roots resolve against that project
directory, so moving the saved file does not change its working directories.
Directories need not exist during import; native loading still checks them.
Missing session names use the source filename without its extension. Conflicting
non-null aliases and malformed scalar or command shapes are rejected.

Lifecycle hooks, project `pre`/`post`, endpoint/runtime overrides, named pane
titles, Teamocil `clear`/filters and other unsupported fields are rejected
before output. Tmuxinator expands ERB templates through Ruby before parsing,
so unexpanded `<%` markup is refused before output or overwrite; Teamocil
evaluates no templates, so the same markup in a Teamocil source is ordinary
text and is preserved. Java creates all panes before sending commands, so
before-command synchronization cannot preserve the source delivery order and is
rejected. Tmuxinator `synchronize: after` uses native `options_after` instead.
Window `pre` requires explicit nonempty panes; otherwise Tmuxinator would omit
that command. Move unsupported behavior into an explicit native workspace or
keep using its source tool.

## Inspect a workspace through MCP

Loaded workspaces are ordinary tmux sessions. Build the separate
[MCP server](../libtmux-mcp/README.md) from the repository root:

```console
$ ./gradlew :libtmux-mcp:installDist \
    --max-workers=2 \
    --no-parallel
```

Configure your MCP client to launch
`libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp` with arguments
`--socket /tmp/libtmux-java-dev/workspace-example.sock` and environment variable
`LIBTMUX_TOOLSETS=inspect`. This selects the same socket as the detached load
above. Use `--socket-name` when loading with `-L`; the endpoint is selected once
at MCP startup.

Discover tools with `tools/list`, then call `list_sessions`, `list_windows`
with `session: dev`, and `list_panes`. Use returned pane IDs with
`capture_pane` or `wait_for_text`; bound `max_lines` and the wait's `timeout`
in seconds. A pending text wait allows other inspection calls on the same
connection. The `tmux://capabilities` resource reports the selected endpoint
and available tools.

## Output and Python

`--json` returns one document; `--ndjson` takes precedence when both are present.
Load and child-process output stream as escaped, flushed events. Machine output
never includes ANSI styling or implicit prompts. Interactive editors and Python
shells use the controlling terminal while their result remains on stdout.

### The machine contract

Every machine-readable record carries `schema_version` and `command`. A `--json`
result is one document; a `--ndjson` stream is one record per line, each with an
`event` and a `sequence` counting from 1. A committed transcript of a load's
stream lives beside the tests, so a record that gains, loses or renames a field
is a diff rather than a surprise.

A failure names one `code`. This is the whole set, shared with the other
ports of this tool, so the same condition answers with the same name whichever
one a script calls:

- **`workspace_not_found`** — the named workspace is not where discovery
  looked
- **`invalid_workspace`** — the document parsed, and does not describe a
  workspace
- **`unsupported_key`** — the document uses a key native loading does not
  implement
- **`session_not_found`** — the session the command names is not on the
  server
- **`session_mismatch`** — the named session exists and is not what the
  document describes
- **`tmux_unavailable`** — tmux could not be found or run
- **`tmux_failed`** — tmux ran and refused, or the server changed under the
  command
- **`script_failed`** — a child program could not be run, or ended badly
- **`destination_exists`** — the destination a capture was told to write is
  taken
- **`usage`** — the command was invoked in a way that cannot be carried out

A layout name is `invalid_workspace` when no tmux accepts it, and `tmux_failed`
when this daemon will not take one another would: `main-horizontal-mirrored`
arrived in 3.5, and `main-h` stopped being a unique abbreviation there. The
document is the same either way; the daemon is not.

Declining a prompt is an answer, not a failure: nothing is written, nothing is
an error, and the command says what it did not do. A prompt that cannot be
asked for want of a terminal is `usage`.

Names outside that set are not a verdict on the request.
`interrupted` says the process was signalled and reports where it stopped, with
exit 130. `log_unavailable` says a `--log-file` sink that opened stopped
accepting records; it is reported separately and leaves the command's own
result and exit status alone. A `--log-file` that cannot be opened in the first
place is `usage`, like any other invocation problem.

A command's `status` is decided by what each of its results retained, not by
which codes its errors carry. Loading two files where the first builds a
session and the second does not match its own is `partial`: the first session
is still there.

An `--ndjson` stream carries these events:

- **`started`** — the command's work began, with `inputs`
- **`workspace-started`** — one input began, with that input's `effects` so
  far
- **`session-created`** — a session this load owns now exists
- **`window-created`** — a window exists, with `pane_total` still to come
- **`pane-created`** — a pane exists; its commands have not been sent
- **`pane-completed`** — a pane has had its commands sent
- **`window-completed`** — a window and every pane in it are done
- **`workspace-completed`** — one input finished, with its `effects`
- **`script-started`** — a child program is about to run
- **`script-output`** — a fragment of a child's output, naming its `stream`
- **`script-completed`** — a child ended, with `child_status` and `truncated`
- **`warning`** — something asked for that will not happen, with its own
  `code`
- **`completed`** — the command finished, carrying its whole result
- **`failed`** — the command stopped, carrying the result up to the failure

A load's `effects` record describes one input: `input`, `input_index`,
`session_id`, `session_name`, `reused`, `owned_session`, `changed`,
`window_total`, `session_pane_total`, `window_ids`, `pane_ids`, and `stage` —
the point the load reached, one of `resolve`, `windows_preflight`,
`before_script`, `options`, `readiness`, `windows`, `commands`, `finalize`,
`completed` or `reused`. A load that removed the session it created adds
`session_removed`. A Python-bridge load adds `engine`, `effects_scope` and
`effects_unknown`, because what it reports is observed topology rather than
operations it performed.

The envelope's `status` is `ok`, `partial` or `error`. `partial` means effects
were retained; `error` means none were.

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
other platforms use known process handles. Successfully detached services
with closed output streams are retained.

## Generated reference and development

Generate metadata from the same graph that parses commands:

```console
$ tmux-workspace --generate schema
```

Generate Bash completion:

```console
$ tmux-workspace --generate bash
```

Generated Bash completion suggests commands, option names and accepted choice
values. It does not query running sessions or discover saved workspace names.

Add `--json` to receive a `schema_version: 1` artifact with `command: generate`,
`format: bash`, the exact completion text in `script`, and `status: ok`.
`--ndjson` returns that artifact as one `completed` event with `sequence: 1`;
it takes precedence when both machine flags are present. Without either flag,
stdout contains the Bash script. Schema generation keeps its metadata document
in every output mode.

For repeated CLI execution while editing, keep Gradle watching the sources:

```console
$ ./gradlew :libtmux-workspace-cli:runDevelopment \
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
$ ./gradlew :libtmux-workspace-cli:check \
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
