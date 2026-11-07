# libtmux-clojure-core-async

Optional [core.async][core-async] integration for
[libtmux-clojure](../libtmux-clojure/README.md). The base façade has no
core.async dependency.

## Install

This adapter ships with the façade's first release; it has no released
coordinate yet. Its Maven coordinate will be
`io.github.libtmux:libtmux-clojure-core-async`. Its future Clojure CLI
dependency is:

```edn
{:deps {io.github.libtmux/libtmux-clojure-core-async
        {:mvn/version "<exact-release>"}}}
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
[changelog](CHANGELOG.md) for its release history.

[core-async]: https://clojure.github.io/core.async/
[guide]: ../docs/guide/clojure.md
