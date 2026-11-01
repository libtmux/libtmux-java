# Changelog

Notable changes to `libtmux-scala-cats`.

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

No entries for `libtmux-scala-cats`.

## 0.1.1 — 2026-11-01

No entries for `libtmux-scala-cats`.

## 0.1.0 — 2026-10-25

No entries for `libtmux-scala-cats`.

## 0.0.1-alpha.19 — 2026-10-24

No entries for `libtmux-scala-cats`.

## 0.0.1-alpha.18 — 2026-10-19

No entries for `libtmux-scala-cats`.

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux-scala-cats`.

## 0.0.1-alpha.16 — 2026-09-26

### Changed

- **`libtmux-scala-cats`'s subsystem accessors answer `F[_]` wrapper classes,
  not the raw Java handle.** `Server.hooks`, `Pane.options`, `Server.shell`,
  `Server.commands`, `Server.buffers`, `Session.environment`,
  `Server.messageLog`, `Server.prompt`, and `Server.keys` used to answer the
  raw Java type, so their operations ran outside `Execution`'s admission bound
  and cancellation. Their reads and mutations now run through `F`, the same as
  every other Cats operation. The direct-style `libtmux-scala` facade is
  unchanged: these accessors still answer the raw Java handle there, since
  direct style is blocking by design. (#23)

## 0.0.1-alpha.15 — 2026-09-26

### Added

- **Scala Cats calls cancel.** Every Cats call, `Batch`, `CommandChain` and
  `Channel` included, can be interrupted; a canceled call ends in
  `Outcome.Canceled`, and fs2 streams wait without a blocking pool. Handles
  have `Eq`, `Hash` and `Show` instances, and `TmuxVersion` an `Order`.
  (#23)

## 0.0.1-alpha.14 — 2026-09-20

No entries for `libtmux-scala-cats`.

## 0.0.1-alpha.13 — 2026-09-20

No entries for `libtmux-scala-cats`.
