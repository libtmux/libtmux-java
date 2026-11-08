# Changelog

Notable changes to `libtmux-clojure-core-async`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

This module has not shipped. Changes that span modules, such as documentation,
CI, and release tooling, are in the [main changelog](../CHANGELOG.md).

## Unreleased

### Added

- **`libtmux-clojure-core-async` adapts control output to core.async.**
  Closeable owners publish output and terminal outcomes through channels,
  stop stalled subscriptions, and leave borrowed connections usable. The core
  package does not require core.async. (#20)
