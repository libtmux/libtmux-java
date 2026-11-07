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

- **The initial optional core.async adapter observes bounded control streams.**
  It owns its subscription, virtual worker, events channel, and terminal
  outcome while leaving a supplied control connection open.
