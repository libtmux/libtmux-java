<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux Clojure core.async adapter">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-clojure-core-async

Optional [core.async][core-async] integration for
[libtmux-clojure][facade]: consume pane output through an events channel and
read shutdown through a separate terminal channel. The base façade has no
core.async dependency.

</div>

## Install

This adapter has not shipped. Its Maven coordinate is
`io.github.libtmux:libtmux-clojure-core-async`; there is no public release to
install yet. From the repository root, install the adapter and its sibling
modules into your local Maven repository:

```console
$ ./gradlew \
    :libtmux:publishToMavenLocal \
    :libtmux-clojure:publishToMavenLocal \
    :libtmux-clojure-core-async:publishToMavenLocal
```

The [complete consumer setup][program] includes a `deps.edn` with the optional
`:core-async` alias and instructions for an isolated Maven repository. Control
observation requires JDK 25 or newer and tmux 3.7 or newer.

## Quick start

Observe pane output, close the events consumer, and read the terminal result.
The fixture supplies socket and binary properties for a private server with
an existing session. Applications supply their own socket path and tmux binary.

<!-- clojure-snippet: control -->
```clojure
(require '[clojure.core.async :as async]
         '[libtmux.control :as control]
         '[libtmux.core :as tm]
         '[libtmux.core-async :as adapter])
(import '[java.time Duration])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path
                                :binary (System/getProperty
                                         "libtmux.docs.binary")})
              connection (control/attach!
                          (:tmux/ref (first (tm/sessions! server)))
                          (Duration/ofSeconds 2))
              ^java.lang.AutoCloseable owner (adapter/observe! connection)]
    (let [pane (first (:reply/lines
                       (control/send! connection
                                      ["new-window" "-P" "-F" "#{pane_id}"
                                       (str "stty -echo; printf docs-async;"
                                            " read stop")])))
          timeout (async/timeout 900)
          [event port] (async/alts!! [(adapter/events owner) timeout]
                                     :priority true)]
      (async/close! (adapter/events owner))
      [(and (identical? port (adapter/events owner)) (= pane (:pane/id event)))
       (:reason (async/<!! (adapter/terminal owner)))])))
;; => [true :adapter-stopped]
```

The documentation suite executes this example against an isolated tmux server
and checks the displayed result. On tmux below 3.7, it checks the control
capability refusal instead.

## Ownership and backpressure

[`observe!`][observe] borrows a control connection and owns its subscription,
virtual worker, events channel, and terminal channel. Closing the adapter or
its events channel stops that work; the borrowed connection stays open.

The adapter defaults to an unbuffered events channel. Choose
`:event-capacity` deliberately, account for its one pending event, and inspect
`:gap` values from the upstream subscription separately from adapter loss.
Do not run blocking control I/O in a `go` block.

The [complete core.async program][program] includes imports, a CLI entry point,
the optional dependency alias, private-server setup and cleanup. It closes its
events consumer, reads the terminal outcome, and reuses the borrowed connection.

The `javadoc` classifier contains native reference HTML and an offline source
browser, generated from this module's var metadata.

## Support and status

Releases are numbered `0.1.N`, and each release increments `N`. The API is
not settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one.

The adapter is unpublished. Its Clojure 1.12.6 CI runs on JDK 25 and 27 with
tmux 3.3a and 3.7c; the older endpoint checks unsupported control behavior.

## Documentation

- [Adapter API][api] — observation, event and terminal channels, and stop.
- [Clojure guide][guide] — capacity, loss, cancellation, and Java interop.
- [Base façade][facade] — captures, native collection processing, and effects.
- [Changelog](CHANGELOG.md) — module-visible changes.
- [Contributing][contributing] — building and testing.
- [License][license] — MIT.

[api]: ../docs/guide/clojure-core-async-api.md
[contributing]: ../.github/CONTRIBUTING.md
[core-async]: https://clojure.github.io/core.async/clojure.core.async.html
[facade]: ../libtmux-clojure/README.md
[guide]: ../docs/guide/clojure.md
[license]: ../LICENSE
[observe]: ../docs/guide/clojure-core-async-api.md#libtmuxcore-asyncobserve
[program]: ../examples/api/README.md#clojure-programs
