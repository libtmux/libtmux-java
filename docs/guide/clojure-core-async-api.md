# libtmux-clojure-core-async API

Generated from public namespace and var metadata; do not edit by hand.
Each heading links to its current Clojure source.

Read the [guide](clojure.md) for runnable programs and ownership.
The [captured-value reference](clojure-reference.md) documents map keys.
Functions ending in `!` may cross the external-state boundary.

## Namespaces

- [`libtmux.core-async`](#libtmuxcore-async)

## [`libtmux.core-async`][libtmux.core-async]

Closeable core.async observation adapter for libtmux control streams.

### [`libtmux.core-async/events`][libtmux.core-async]

`(events adapter)`

Returns the read-only events channel. Closing it stops the adapter.

### [`libtmux.core-async/limits`][libtmux.core-async]

`(limits adapter)`

Returns the adapter's exact retained-event bound.

### [`libtmux.core-async/observe!`][libtmux.core-async]

`(observe! connection)`

`(observe! connection options)`

Starts an owned virtual worker over a borrowed control connection.

`:event-capacity` defaults to zero. The worker can retain one pending event;
a fixed buffer retains that many more. `:upstream-capacity` bounds the Java
subscription, which reports overflow as a :gap value ahead of the events that
remain; `:encoding` is :bytes or :utf-8. Closing the events channel or
adapter stops blocked reads and puts. A stopped outcome retains the final
:upstream/terminal, including loss, cause and trailing text. Adapter loss
stays separate under :adapter/events. The borrowed connection remains open.

### [`libtmux.core-async/stop!`][libtmux.core-async]

`(stop! adapter)`

Stops observation and closes only the owned subscription and worker.

### [`libtmux.core-async/terminal`][libtmux.core-async]

`(terminal adapter)`

Returns a buffered channel that delivers one outcome after cleanup.

[libtmux.core-async]:
  ../../libtmux-clojure-core-async/src/main/clojure/libtmux/core_async.clj
