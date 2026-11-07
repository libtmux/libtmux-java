# Changelog

Notable changes to `libtmux-clojure`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

This module has not shipped. Changes that span modules, such as documentation,
CI, and release tooling, are in the [main changelog](../CHANGELOG.md).

## Unreleased

### Changed

- **Raw task and runtime constructors are private.** Create owned workers
  with `runtime!` and submit work through `submit!`.

### Fixed

- **Text subscriptions reset their own decoder after reported gaps.**
  Decoder eviction is visible, trailing incomplete bytes survive in the
  terminal outcome, and malformed bytes remain recoverable as `\\xHH`.
- **Control streams bound queued payload bytes and classify termination.**
  `:max-bytes` defaults to 16 MiB. Loss includes byte counts; terminal
  `:reason` distinguishes control exit, transport/protocol failure, and
  unknown EOF from intentional closure.

### Added

- **The initial Clojure façade exposes captured data and explicit operations.**
  `libtmux.core`, `libtmux.data`, `libtmux.async`, and `libtmux.control` keep
  captured traversal local while making acquisition, mutation, cancellation,
  and streaming ownership explicit.
