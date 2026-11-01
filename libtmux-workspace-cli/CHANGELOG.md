# Changelog

Notable changes to `libtmux-workspace-cli`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

The modules are released together at one version, so every release since this
module first shipped has a section here. Changes that span modules, such as
documentation, CI and release tooling, are in the [main
changelog](../CHANGELOG.md).

## Unreleased

## 0.1.2 — 2026-11-01

No entries for `libtmux-workspace-cli`.

## 0.1.1 — 2026-11-01

No entries for `libtmux-workspace-cli`.

## 0.1.0 — 2026-10-25

No entries for `libtmux-workspace-cli`.

## 0.0.1-alpha.19 — 2026-10-24

### Changed

- **Python extensions require optional-policy v2.** `tmux-workspace shell` and
  extension loading need a Python libtmux with
  `TMUX_OPTIONAL_FEATURE_POLICY_VERSION >= 2`. No released libtmux has it yet;
  it comes with tmux-python/libtmux#780, so until then install libtmux from
  that source or leave `shell` and extensions alone. (#72)

## 0.0.1-alpha.18 — 2026-10-19

### Added

- **`tmux-workspace` reports a tmux older than 3.3a as `tmux_unsupported`.**
  The code appears in JSON output beside the message, which says to upgrade
  tmux or use the previous release. (#69)

### Changed

- **The workspace CLI's attached handoff starts no daemon.** `workspace load`
  passes `-N` to the `attach-session` it runs. (#69)

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux-workspace-cli`.

## 0.0.1-alpha.16 — 2026-09-26

### Added

- **`tmux-workspace` is on Maven Central as
  `io.github.libtmux:libtmux-workspace-cli`.** It releases with the other
  artifacts, at the same version, and `libtmux-bom` manages it. Its main class
  is `io.github.libtmux.workspace.cli.Main`. (#23)

- **`tmux-workspace` discovers, loads, freezes, converts, imports and searches
  workspaces.** Command names and flags follow tmuxp 1.74.0. Every command
  answers in JSON or NDJSON on request, and the CLI adds terminal progress,
  diagnostics, and Bash, Zsh and Fish completion. (#16)

- **`tmux-workspace load` checks every input before it builds anything.** It
  supports pane commands, directories, environment, options, window indexes,
  focus, scripts, attaching and appending. A failed load removes the sessions
  it created, and an interrupted load or a failed append reports what it left
  behind. (#16)

- **`tmux-workspace` checks a workspace's layouts against the target tmux
  before creating anything.** Presets resolve by that tmux's version, and pane
  counts must fit. On tmux 3.8 and later, a JSON layout's cell structure is
  checked too. (#16)

- **`tmux-workspace` runs tmuxp Python extensions and shells.** That bridge
  needs tmuxp 1.74.0 and uses the selected tmux binary; native commands never
  start Python. (#16)
