# Changelog

Notable changes to `libtmux-junit5`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

The modules are released together at one version, so every release since this
module first shipped has a section here. Changes that span modules, such as
documentation, CI and release tooling, are in the [main
changelog](../CHANGELOG.md).

## Unreleased

## 0.1.1 — 2026-11-01

No entries for `libtmux-junit5`.

## 0.1.0 — 2026-10-25

No entries for `libtmux-junit5`.

## 0.0.1-alpha.19 — 2026-10-24

No entries for `libtmux-junit5`.

## 0.0.1-alpha.18 — 2026-10-19

No entries for `libtmux-junit5`.

## 0.0.1-alpha.17 — 2026-09-26

No entries for `libtmux-junit5`.

## 0.0.1-alpha.16 — 2026-09-26

No entries for `libtmux-junit5`.

## 0.0.1-alpha.15 — 2026-09-26

### Added

- **`FakeTmux` answers control clients.** A test can attach a control client
  to it and push output and notifications to that client, without tmux.
  (#23)

### Fixed

- **`TmuxExtension` reaps a fixture whose pid was reused.** A killed run's
  fixture is named by its JVM's start as well as its pid, so a live process
  that later holds the pid no longer keeps it. (#23)

## 0.0.1-alpha.14 — 2026-09-20

No entries for `libtmux-junit5`.

## 0.0.1-alpha.13 — 2026-09-20

No entries for `libtmux-junit5`.

## 0.0.1-alpha.12 — 2026-09-19

### Added

- **`FakeTmux` tests library consumers without starting tmux.** It is available
  in `libtmux-junit5`. (#17)

### Fixed

- **`NamedServerFixture` accepts platform temporary-directory symlinks.** (#17)

## 0.0.1-alpha.11 — 2026-09-12

No entries for `libtmux-junit5`.

## 0.0.1-alpha.10 — 2026-09-06

No entries for `libtmux-junit5`.

## 0.0.1-alpha.9 — 2026-09-06

### Added

- **`NamedServerFixture` safely owns explicitly named test servers.** It binds
  teardown to the reported process, socket path, and inode, then fails closed if
  any of that identity changes before cleanup.

## 0.0.1-alpha.8 — 2026-08-30

### Fixed

- **`TmuxExtension` proves abandoned servers exited before deleting their
  directories.** Successful recovery also removes the abandoned directory.
  (#6)

## 0.0.1-alpha.7 — 2026-08-22

No entries for `libtmux-junit5`.

## 0.0.1-alpha.6 — 2026-08-16

No entries for `libtmux-junit5`.

## 0.0.1-alpha.5 — 2026-08-16

No entries for `libtmux-junit5`.

## 0.0.1-alpha.3 — 2026-08-16

### Fixed

- **The fixture's sweep counted servers it had only asked to stop.**
  `TmuxExtension` ended an abandoned server with `destroy`, which sends SIGTERM
  and returns; tmux answers that by destroying every session and reaping each
  pane's children first, so the process outlived the signal by up to 244ms under
  load. The sweep now waits for each exit and counts what ended, which is what it
  always claimed to return. The tmux matrix failed on that margin on its slowest
  lane.

## 0.0.1-alpha.2 — 2026-08-16

No entries for `libtmux-junit5`.

## 0.0.1-alpha.1 — 2026-08-16

### Added

- **Teardown that outlives the process meant to do it.** A test JVM killed
  outright left a tmux server running, and once the host's temporary-file
  cleaner removed its directory the socket could not be reached to kill it —
  nineteen such servers were found on this machine. `TmuxExtension` now names
  the owning JVM in the socket path, ends its own servers from a shutdown hook,
  and sweeps servers whose owner is gone before making its first one. A live
  owner is never touched, so Gradle's per-module workers and the matrix's eight
  lanes can share one root. Measurements, and the two rejected designs, in
  [`docs/decisions/0006`](../docs/decisions/0006-real-tmux-junit5-fixture-lifecycle.md).
