# Changelog

Notable changes to `libtmux-clojure`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

This module has not shipped. Changes that span modules, such as documentation,
CI, and release tooling, are in the [main changelog](../CHANGELOG.md).

## Unreleased

### Added

- **`libtmux-clojure` adds Clojure APIs over the Java engine.** Use captured
  maps and vectors, native collection queries, explicit tmux operations, and
  owned tasks and streams. Error data preserves dispatch certainty and
  possible partial effects. (#20)
- **`libtmux.core/start!` starts a private tmux server for `with-open`.** Closing
  it stops tmux and removes its directory. (#142)
