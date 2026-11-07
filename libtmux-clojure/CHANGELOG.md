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

- **The initial Clojure façade exposes captured data and explicit operations.**
  `libtmux.core`, `libtmux.data`, `libtmux.async`, and `libtmux.control` keep
  captured traversal local while making acquisition, mutation, cancellation,
  and streaming ownership explicit.
