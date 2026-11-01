# Changelog

Notable changes to `libtmux-workspace`.

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

No entries for `libtmux-workspace`.

## 0.1.1 — 2026-11-01

No entries for `libtmux-workspace`.

## 0.1.0 — 2026-10-25

No entries for `libtmux-workspace`.

## 0.0.1-alpha.19 — 2026-10-24

No entries for `libtmux-workspace`.

## 0.0.1-alpha.18 — 2026-10-19

No entries for `libtmux-workspace`.

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux-workspace`.

## 0.0.1-alpha.16 — 2026-09-26

### Fixed

- **`WorkspaceBuilder` rebalances the window after each pane split**, so a
  window with more than four panes builds at the default terminal size. (#16)

- **`WorkspaceBuilder` refuses a layout with fewer cells than the window has
  panes**, checked against the target tmux before any window is created. (#16)

- **`WorkspaceBuilder` accepts a layout captured from a JSON-layout session**,
  refusing it only when the target tmux predates 3.8, rather than rejecting
  every JSON layout as an unknown name. (#16)

## 0.0.1-alpha.15 — 2026-09-26

No entries for `libtmux-workspace`.

## 0.0.1-alpha.14 — 2026-09-20

No entries for `libtmux-workspace`.

## 0.0.1-alpha.13 — 2026-09-20

No entries for `libtmux-workspace`.

## 0.0.1-alpha.12 — 2026-09-19

No entries for `libtmux-workspace`.

## 0.0.1-alpha.11 — 2026-09-12

No entries for `libtmux-workspace`.

## 0.0.1-alpha.10 — 2026-09-06

No entries for `libtmux-workspace`.

## 0.0.1-alpha.9 — 2026-09-06

No entries for `libtmux-workspace`.

## 0.0.1-alpha.8 — 2026-08-30

### Fixed

- **Workspace input is validated before session creation.** Unsupported
  layouts, unsafe names, malformed topology, and uncertain creation replies
  leave tmux untouched or roll back the exact staging session. (#6)

## 0.0.1-alpha.7 — 2026-08-22

No entries for `libtmux-workspace`.

## 0.0.1-alpha.6 — 2026-08-16

No entries for `libtmux-workspace`.

## 0.0.1-alpha.5 — 2026-08-16

No entries for `libtmux-workspace`.

## 0.0.1-alpha.3 — 2026-08-16

No entries for `libtmux-workspace`.

## 0.0.1-alpha.2 — 2026-08-16

No entries for `libtmux-workspace`.

## 0.0.1-alpha.1 — 2026-08-16

No entries for `libtmux-workspace`.
