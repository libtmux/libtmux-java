# libtmux-clojure

**A Clojure façade over libtmux-java.**

It returns captured persistent maps and vectors, with the Java handle retained
at `:tmux/ref` for an explicit effect. Ordinary Clojure collection operations
work on the captured values without acquiring new tmux state.

> **Alpha.** The API will change without notice. Pin an exact version; it is
> not recommended for production.

Requires JDK 21 or newer and tmux 3.2a through 3.7c.

## Install

Add the direct coordinate to `deps.edn`:

```edn
{:deps {io.github.libtmux/libtmux-clojure
        {:mvn/version "0.0.1-alpha.12"}}}
```

The Maven coordinate is `io.github.libtmux:libtmux-clojure`. The Java BOM also
manages it when a Java build owns versions:

```xml
<dependency>
  <groupId>io.github.libtmux</groupId>
  <artifactId>libtmux-clojure</artifactId>
  <version>0.0.1-alpha.12</version>
</dependency>
```

## Connect and acquire

`open!` opens a local client. It does not start or stop the tmux daemon.
Every acquisition function ends in `!`; traversal through a captured map is
local. The example uses the socket property supplied by the executable
documentation fixture. An application supplies its own explicit path.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (:session/name (tm/new-session! server {:name "docs-connect"}))))
;; => "docs-connect"
```

`with-open` releases the client when its body finishes. It never kills the
tmux daemon. `kill-server!` is the explicit operation that does that.

## Values and effects

Values are observations. Editing a map changes only that map. An operation
must receive its preserved `:tmux/ref`, and a refresh returns a new observation.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (let [session (tm/new-session! server {:name "docs-identity"})]
      (data/same-entity? session (tm/refresh! (:tmux/ref session))))))
;; => true
```

Use `data/data` when a value must leave the operational boundary; it removes
every `:tmux/ref` and rejects unsupported JVM objects.

## Reference

- [`libtmux.core`](src/main/clojure/libtmux/core.clj): acquisition, effects,
  errors, options, buffers and command groups.
- [`libtmux.data`](src/main/clojure/libtmux/data.clj): captured values,
  identity, projections, cardinality and Java predicate adapters.
- [`libtmux.async`](src/main/clojure/libtmux/async.clj): bounded owned tasks
  and `CompletionStage` interop.
- [`libtmux.control`](src/main/clojure/libtmux/control.clj): explicit control
  replies and bounded event observation.

[`docs/guide/clojure.md`](../docs/guide/clojure.md) covers filtering, links,
error metadata, lifecycle, execution modes, streaming and Java interop.
[`docs/guide/clojure-reference.md`](../docs/guide/clojure-reference.md) lists
captured keys, relations and Java filter support.
