<!-- libtmux-logo -->
<div align="center">
  <picture>
    <source srcset="assets/logo.svg" type="image/svg+xml">
    <img src="assets/logo.png" width="128" height="128" alt="libtmux for Clojure">
  </picture>
</div>
<!-- /libtmux-logo -->

<div align="center">

# libtmux-clojure

**libtmux for Clojure:** a data-first façade over
[libtmux-java][java-core]. It acquires tmux state as persistent maps and
vectors, retains a Java handle at `:tmux/ref` for an explicit operation, and
leaves ordinary Clojure collection processing local and unconstrained.

</div>

## Install

The Clojure façade has not shipped. Its Maven coordinate is
`io.github.libtmux:libtmux-clojure`; there is no public release to install yet.
Build and verify it from this checkout:

```console
$ ./gradlew :libtmux-clojure:check :integration-tests:clojureIntegrationTest
```

The façade requires JDK 25 or newer. The project tests tmux 3.3a through
3.7c; [control streaming][guide-streaming] requires tmux 3.7 or newer.

## Quick start

`open!` connects to an explicit socket and owns only the local client.
`snapshot!` acquires one captured graph. The `data/windows` and `data/panes`
walks below read that graph; they do not issue another tmux command.

The example runner supplies `libtmux.docs.socket` for its private server. In a
REPL, replace that property lookup with the path to your server's socket.
The [complete programs][programs] include a launcher that sets up and removes
an isolated server for you.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (tm/new-session! server {:name "docs-quick-start"})
    (let [snapshot (tm/snapshot! server)
          session (first (filter #(= "docs-quick-start" (:session/name %))
                                 (:tmux/sessions snapshot)))]
      [(:session/name session)
       (count (data/windows session))
       (count (data/panes session))])))
;; => ["docs-quick-start" 1 1]
```

Use `with-open` for every client, runtime, control connection, and
subscription that this program owns. Closing a `Server` releases client
resources; it does not kill the tmux server.

## Namespaces

- [`libtmux.core`][core-api] — acquisition, effects, options, and command
  groups.
- [`libtmux.data`][data-api] — captured traversal, projections, identity,
  cardinality, and Java predicate adapters.
- [`libtmux.async`][async-api] — bounded tasks, deadlines, cancellation, and
  CompletionStage interop.
- [`libtmux.control`][control-api] — explicit control replies and owned
  output subscriptions.

## Run complete programs

The [Clojure API programs][programs] include their imports, entry points,
consumer dependencies, and private-server launcher. Start with captured
session/window/pane traversal, then compare native and Java predicates, create
a detached window, or send literal input and verify its captured output.
The capability programs add layout/movement, bounded capture and cancellation,
control output, and Java command exit results. CI executes every program and
checks its displayed result.

## What it leaves out

- **No hidden refreshes.** Acquisition and mutation end in `!`; captured
  traversal and native `filter`, `map`, `into`, and transducers stay local.
- **No second query engine.** Use Clojure predicates for local logic, or adapt
  a canonical Java `FilterExpr` when its model and serialization semantics
  matter. The façade does not yet accept an EDN query language.
- **No editable remote maps.** `assoc` changes ordinary data, not tmux. Keep
  `:tmux/ref` for an explicit effect, or use `data/data` for a detached EDN
  projection.
- **No implicit asynchronous runtime.** `libtmux.async` is bounded and
  closeable. The `core.async` bridge is a separate optional artifact.
- **No Clojure MCP server.** Model-facing tools belong to
  [libtmux-mcp][mcp-guide]; this façade remains a JVM library.
- **No ClojureScript, Babashka, or native-image target.** The operational API
  depends on Java handles and the Java transport.

## Support and status

Releases are numbered `0.1.N`, and each release increments `N`. The API is
not settled: any release may change or remove exported identifiers, and the
changelog marks each break. Pin an exact version to avoid one.

Only the newest release is supported, with no backports. The Clojure façade
is currently pre-release; CI tests Clojure 1.12.6 on JDK 25 and 27 against
the supported tmux endpoints, 3.3a and 3.7c. [Report a defect][contributing]
with the tmux version, endpoint choice, and the smallest reproducer.

## Documentation

- [Clojure guide][guide] — captures, links, filtering, effects, control
  streams, `core.async`, cancellation, and Java interop.
- [Captured-data reference][reference] — every map key, relation, identity
  rule, and Java filter boundary.
- [Native API reference][api] — public namespaces, arities, and docstrings,
  generated from the Clojure source and linked to it.
- [Optional `core.async` adapter][core-async] — ownership, capacity, loss, and
  terminal outcomes.
- [MCP guide][mcp-guide] — the model-facing server and its explicit boundary
  from this JVM façade.
- [Benchmarking guide][benchmarks] — how the Java operation measurements are
  run. It does not yet claim a façade-specific benchmark.
- [Changelog](CHANGELOG.md) — module-visible changes once this façade ships.

Every Clojure snippet in this README and the guide runs against an isolated
tmux server in `:integration-tests:clojureIntegrationTest`; no snippet is
illustrative pseudocode.

The module's `javadoc` classifier contains native Clojure reference HTML and
an offline source browser. Build it with `:libtmux-clojure:clojureApiJar`;
the generated site starts at `build/docs/clojure/index.html` in this module.

[benchmarks]: ../benchmarks/README.md
[api]: ../docs/guide/clojure-api.md
[async-api]: ../docs/guide/clojure-api.md#libtmuxasync
[contributing]: ../.github/CONTRIBUTING.md
[control-api]: ../docs/guide/clojure-api.md#libtmuxcontrol
[core-api]: ../docs/guide/clojure-api.md#libtmuxcore
[core-async]: ../libtmux-clojure-core-async/README.md
[data-api]: ../docs/guide/clojure-api.md#libtmuxdata
[guide]: ../docs/guide/clojure.md
[guide-streaming]: ../docs/guide/clojure.md
[java-core]: ../libtmux/README.md
[mcp-guide]: ../docs/guide/mcp.md
[programs]: ../examples/api/README.md#clojure-programs
[reference]: ../docs/guide/clojure-reference.md
