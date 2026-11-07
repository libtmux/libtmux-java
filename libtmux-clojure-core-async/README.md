# libtmux-clojure-core-async

Optional [core.async][core-async] integration for
[libtmux-clojure](../libtmux-clojure/README.md). The base façade has no
core.async dependency.

## Install

This adapter has not shipped. Its Maven coordinate is
`io.github.libtmux:libtmux-clojure-core-async`; there is no public release to
install yet. Build and verify it from this checkout:

```console
$ ./gradlew :libtmux-clojure-core-async:check
```

## Ownership and backpressure

`observe!` borrows a control connection and owns its subscription, virtual
worker, events channel, and terminal channel. Closing the adapter or its
events channel stops that owned work; it never closes the borrowed connection.

The adapter defaults to an unbuffered events channel. Choose
`:event-capacity` deliberately, account for its one pending event, and inspect
`:gap` values from the upstream subscription separately from adapter loss.
Do not run blocking control I/O in a `go` block.

The [Clojure guide][guide] contains executable control and core.async examples,
including terminal outcomes and shutdown. See this module's
[native API reference][api] for function arities and ownership contracts,
and its [changelog](CHANGELOG.md) for release history.

The [complete core.async program][program] includes imports, a CLI entry point,
the optional dependency alias, private-server setup and cleanup. It closes its
events consumer, reads the terminal outcome, and reuses the borrowed connection.

The `javadoc` classifier contains native reference HTML and an offline source
browser, generated from this module's var metadata.

[api]: ../docs/guide/clojure-core-async-api.md
[core-async]: https://clojure.github.io/core.async/
[guide]: ../docs/guide/clojure.md
[program]: ../examples/api/README.md#clojure-programs
