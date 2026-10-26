<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Java workspace">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-workspace-cli

**`tmux-workspace` loads, captures, converts and imports tmux workspaces.**

[![libtmux.org](https://img.shields.io/badge/docs-libtmux.org-0b7285)](https://libtmux.org/en/java/latest/workspace/)
[![libtmux-workspace-cli](https://img.shields.io/maven-central/v/io.github.libtmux/libtmux-workspace-cli?label=libtmux-workspace-cli)](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace-cli)

</div>

[Load][cli-load] a [tmuxp][tmuxp]-style file into a session,
[capture][cli-freeze] a session back into a file, [convert][cli-convert] it
between YAML and JSON, or [import][cli-import] a tmuxinator or Teamocil project.
Flags follow tmuxp 1.74.0, and every command can answer in JSON or NDJSON.

`io.github.libtmux:libtmux-workspace-cli` — [on Maven
Central](https://central.sonatype.com/artifact/io.github.libtmux/libtmux-workspace-cli).
Reference: the [command manual][cli-manual] on libtmux.org.

> Releases are numbered `0.1.N`, and each release increments `N`. The API is
> not settled: any release may change or remove exported identifiers, and the
> changelog marks each break. Pin an exact version to avoid one.
>
> Commands, flags and output records are part of the API.

**Contents** — [Quick start](#quick-start) · [Find, convert, and
import](#find-convert-and-import) · [Inspect a loaded workspace through
MCP](#inspect-a-loaded-workspace-through-mcp) · [Commands](#commands) · [What it
leaves out](#what-it-leaves-out) · [Support and status](#support-and-status) ·
[Documentation](#documentation)

## Quick start

Build the launcher from the repository root and put its directory on `PATH`:

```console
$ ./gradlew :libtmux-workspace-cli:installDist
```

```console
$ export PATH="$PWD/libtmux-workspace-cli/build/install/tmux-workspace/bin:$PATH"
```

Check that it runs:

```console
$ tmux-workspace --version
```

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

### [`tmux-workspace load`][cli-load]: build a session

[`load`][cli-load] builds the workspace as a session. `-d` leaves it detached
and `-S` names the tmux socket, so a tmux you have open is untouched:

```console
$ mkdir -p /tmp/libtmux-java-dev
```

```console
$ tmux-workspace load ./workspace.yaml \
    -S /tmp/libtmux-java-dev/workspace-example.sock \
    -d \
    --ndjson
```

It starts a tmux server on that socket and prints one JSON event per line. The
last is a `completed` event with `status` `ok`, and the session `dev` now holds
two panes.

Every layout is checked before any script runs or any session changes.

### [`tmux-workspace freeze`][cli-freeze]: capture a session

[`freeze`][cli-freeze] writes a live session back out as a workspace:

```console
$ tmux-workspace freeze dev \
    -S /tmp/libtmux-java-dev/workspace-example.sock \
    --json
```

It prints the workspace as one JSON document: `session_name` is `dev`, and the
window lists two panes.

It recovers topology, directories, focus and options. It does not recover
command history.
Writing the capture to a file takes `--save-to`.

## Find, convert, and import

### [`tmux-workspace ls`][cli-ls]: what is saved

[`ls`][cli-ls] lists the workspaces in the current project and in the global
configuration directory:

```console
$ tmux-workspace ls
```

It prints one line for each workspace, its name and where it lives.

### [`tmux-workspace search`][cli-search]: find one by what is in it

[`search`][cli-search] looks through names, paths, sessions, windows and pane
commands. This finds the workspaces with `editor` in a window's name:

```console
$ tmux-workspace search editor --field window
```

Every query must match unless you add `--any`.

### [`tmux-workspace convert`][cli-convert]: YAML to JSON and back

[`convert`][cli-convert] rewrites a workspace file in the other format. It
never runs the workspace:

```console
$ tmux-workspace convert ./workspace.yaml --save-to ./workspace.json --yes
```

It asks before writing unless you pass `--yes`, and it keeps an existing file
unless you pass `--force`.

### [`tmux-workspace import`][cli-import]: bring a tmuxinator or Teamocil file

[`import tmuxinator`][cli-import-tmuxinator] and
[`import teamocil`][cli-import-teamocil] translate the other tool's file into a
native workspace:

```console
$ tmux-workspace import tmuxinator ./project.yml --save-to ./imported.yaml --yes
```

A field it cannot carry is rejected before anything is written.

## Inspect a loaded workspace through MCP

A loaded workspace is an ordinary tmux session, so an agent can read it through
[`libtmux-mcp`](../libtmux-mcp/README.md). Build the launcher:

```console
$ ./gradlew :libtmux-mcp:installDist \
    --max-workers=2 \
    --no-parallel
```

### `claude mcp add`: read the socket `load` built

Register it on that socket, offering only the tools that read tmux:

```console
$ claude mcp add tmux \
    -e LIBTMUX_TOOLSETS=inspect \
    -- libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp \
    --socket /tmp/libtmux-java-dev/workspace-example.sock
```

Another client launches the same program with the same arguments and
environment variable. Use `--socket-name` when you loaded with `-L`.

Then the agent can:

- Find the panes with [`list_sessions`][tool-list-sessions],
  [`list_windows`][tool-list-windows] with `session_name: dev`, and
  [`list_panes`][tool-list-panes].
- Read one with [`capture_pane`][tool-capture-pane], or wait for text with
  [`wait_for_text`][tool-wait-for-text].
- Read the selected endpoint from the `tmux://capabilities` resource.

## Commands

| command | what it does | reference |
| --- | --- | --- |
| `load` | builds workspaces in order, creating or reusing a session | [`load`][cli-load] |
| `freeze` | captures a running session as a workspace file | [`freeze`][cli-freeze] |
| `convert` | converts a workspace file between YAML and JSON | [`convert`][cli-convert] |
| `import tmuxinator`, `import teamocil` | translates another tool's file into a workspace | [`import`][cli-import], [`tmuxinator`][cli-import-tmuxinator], [`teamocil`][cli-import-teamocil] |
| `ls` | lists the local project and global workspaces | [`ls`][cli-ls] |
| `search` | searches names, paths, sessions, windows and pane commands | [`search`][cli-search] |
| `edit` | resolves a workspace and opens it in `EDITOR` | [`edit`][cli-edit] |
| `shell` | opens a Python shell on a session; needs Python with tmuxp 1.74.0 | [`shell`][cli-shell] |
| `debug-info` | reports runtime and configuration diagnostics | [`debug-info`][cli-debug-info] |
| `--generate` | writes the command schema or Bash, Zsh or Fish completion | [completion][cli-completion] |

## What it leaves out

- **Rollback.** A failed append reports what it kept and undoes nothing.
  [Creating, reusing and appending][ref-loading] has the rules.
- **tmuxp's layout and focus defaults.** A window with no `layout` tiles its
  panes where tmuxp stacks them, and no `focus` keeps the first window active.
  [Layouts and focus][ref-loading] says why.
- **Unknown keys.** Loading rejects a key it does not implement before it
  contacts tmux, except `x-` vendor extensions. [Document rules][ref-rules] has
  the rest.
- **Fields an import cannot carry.** Hooks, project `pre` and `post`, endpoint
  overrides and the like are rejected. [Imports][ref-imports] lists them.
- **Python, unless you ask.** Loading, capture, conversion, imports, discovery
  and search run on the JVM. [Python][ref-python] covers the `shell` command,
  plugins and custom builders.

## Support and status

- **JDK 25 or newer**, and **tmux 3.3a or newer**.
- **Linux, including WSL.** CI runs on Ubuntu. macOS is supported on a
  best-effort basis, and CI coverage for it is highly limited.
- **Releases.** Releases are numbered `0.1.N`, and each release increments
  `N`. The API is not settled: any release may change or remove exported
  identifiers, and the changelog marks each break. Pin an exact version to
  avoid one.
  Commands, flags and output records are part of the API.
- **Machine output.** `--json` is one document and `--ndjson` one record per
  line. A failure names one `code`, and the exit status is `0`, `1`, `2` or
  `130`. [Output][ref-output] has the full contract.
- **Optional Python.** Only `shell`, tmuxp plugins and custom builders need
  Python with tmuxp 1.74.0, named by `TMUX_WORKSPACE_PYTHON`, and a Python
  libtmux whose `TMUX_OPTIONAL_FEATURE_POLICY_VERSION` is 2 or more. No
  released libtmux has that yet; it comes with
  [tmux-python/libtmux#780](https://github.com/tmux-python/libtmux/pull/780),
  so install libtmux from that source or skip those features.

## Documentation

- [The `tmux-workspace` reference][ref] — loading, document rules, imports,
  output, Python, and working on the CLI itself.
- [The command manual][cli-manual] — a page for each command, with the
  [configuration][configuration] and [output][output-reference] references.
- [`libtmux-workspace`](../libtmux-workspace/README.md) — the library that
  builds a workspace from Java.

[cli-completion]: https://libtmux.org/en/java/latest/workspace/cli/completion/
[cli-convert]: https://libtmux.org/en/java/latest/workspace/cli/convert/
[cli-debug-info]: https://libtmux.org/en/java/latest/workspace/cli/debug-info/
[cli-edit]: https://libtmux.org/en/java/latest/workspace/cli/edit/
[cli-freeze]: https://libtmux.org/en/java/latest/workspace/cli/freeze/
[cli-import]: https://libtmux.org/en/java/latest/workspace/cli/import/
[cli-import-teamocil]: https://libtmux.org/en/java/latest/workspace/cli/import-teamocil/
[cli-import-tmuxinator]: https://libtmux.org/en/java/latest/workspace/cli/import-tmuxinator/
[cli-load]: https://libtmux.org/en/java/latest/workspace/cli/load/
[cli-ls]: https://libtmux.org/en/java/latest/workspace/cli/ls/
[cli-manual]: https://libtmux.org/en/java/latest/workspace/cli/
[cli-search]: https://libtmux.org/en/java/latest/workspace/cli/search/
[cli-shell]: https://libtmux.org/en/java/latest/workspace/cli/shell/
[configuration]: https://libtmux.org/en/java/latest/workspace/configuration/
[output-reference]: https://libtmux.org/en/java/latest/workspace/reference/output/
[ref]: ../docs/guide/workspace-cli.md
[ref-imports]: ../docs/guide/workspace-cli.md#imports
[ref-loading]: ../docs/guide/workspace-cli.md#loading
[ref-output]: ../docs/guide/workspace-cli.md#output
[ref-python]: ../docs/guide/workspace-cli.md#python
[ref-rules]: ../docs/guide/workspace-cli.md#document-rules
[tmuxp]: https://tmuxp.git-pull.com/
[tool-capture-pane]: https://libtmux.org/en/java/latest/mcp/tools/capture_pane/
[tool-list-panes]: https://libtmux.org/en/java/latest/mcp/tools/list_panes/
[tool-list-sessions]: https://libtmux.org/en/java/latest/mcp/tools/list_sessions/
[tool-list-windows]: https://libtmux.org/en/java/latest/mcp/tools/list_windows/
[tool-wait-for-text]: https://libtmux.org/en/java/latest/mcp/tools/wait_for_text/
