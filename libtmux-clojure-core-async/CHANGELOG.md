# Changelog

Notable changes to `libtmux-clojure-core-async`.

Releases are numbered `0.1.N`, and each release increments `N`. The API is not
settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one. Only the newest
release is supported, and there are no backports.

This module has not shipped. Changes that span modules, such as documentation,
CI, and release tooling, are in the [main changelog](../CHANGELOG.md).

## Unreleased

### Fixed

- **Stopping preserves the subscription's final loss and trailing text.**
  A blocked channel put no longer replaces known upstream loss with zero.
  `:upstream/terminal` retains the original end reason, cause, and pending
  UTF-8 tail; `:adapter/events` stays separate from upstream counters.

### Added

- **The initial optional core.async adapter observes bounded control streams.**
  It owns its subscription, virtual worker, events channel, and terminal
  outcome while leaving a supplied control connection open.
