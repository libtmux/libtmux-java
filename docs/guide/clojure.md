# Clojure

`libtmux-clojure` is a JVM façade over the Java library. It keeps tmux command
encoding, transport, server identity and compatibility in Java, while exposing
captured persistent Clojure values and ordinary collection operations.

Every Clojure fence in reader-facing documentation is discovered and run against
an owned tmux server. A fence must open and close its own client with
`with-open`
and end with one `;; =>` EDN result. The test fixture supplies
`libtmux.docs.socket` and `libtmux.docs.binary`; applications supply their
socket path and use their selected binary or the default `tmux` command.

## Values, links and projection

`sessions!`, `windows!`, `panes!` and `clients!` acquire a graph. `data/windows`
and `data/panes` traverse that graph without I/O. A window occurrence has a
contextual `:tmux/link`; `data/same-link?` includes the session, window and
index, while `data/same-entity?` compares only the physical identity.

`nil` means an optional relationship or lookup was absent in a successful
capture. Failed acquisition throws an `ExceptionInfo`; it is never represented
as an empty collection. `data/data` returns a detached EDN projection without
the Java refs required for effects or Java filter evaluation.

## Resource scopes and laziness

Acquisition returns eager captured values. A lazy `filter` or `map` over those
values reads only Clojure data, so it can escape the `with-open` scope. An I/O
operation such as `capture!` is different: realize it inside the scope that
owns its server. Do not return `(map #(tm/capture! (:tmux/ref %)) panes)` from
`with-open`; use `mapv`, `into`, or another eager reduction before closing.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))
      {:keys [lazy-names captures]}
      (with-open [server (tm/open! {:socket-path socket-path})]
        (let [session (tm/new-session! server {:name "docs-lazy"})
              pane (first (data/panes (first (data/windows session))))]
          {:lazy-names (->> (tm/sessions! server)
                            (filter #(= "docs-lazy" (:session/name %)))
                            (map :session/name))
           :captures (mapv #(tm/capture! (:tmux/ref %) {:from :history})
                           [pane])}))]
  [(vec lazy-names) (count captures)])
;; => [["docs-lazy"] 1]
```

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (let [session (tm/new-session! server {:name "docs-projection"})]
      (data/data (select-keys session [:tmux/ref :session/name])))))
;; => {:session/name "docs-projection"}
```

## Native filtering and Java expressions

Use `filter`, `map`, `into` and transducers for unrestricted local Clojure
logic. Java `FilterExpr` remains available when an expression must use its
canonical Java fields and relationship semantics. `data/predicate` returns an
IFn. `data/matching` returns a transducer with one argument and a lazy sequence
with an expression and collection. Both evaluate preserved `:tmux/ref` values,
not fields edited in a Clojure map.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])
(import '[io.github.libtmux Session_])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (tm/new-session! server {:name "docs-query"})
    (->> (tm/sessions! server)
         (data/matching (.is (Session_/name) "docs-query"))
         (mapv :session/name))))
;; => ["docs-query"]
```

Use `data/exactly-one` when zero or many candidates is an error, and
`data/one-or-none` when absence is allowed. Establish cardinality before any
effect.

## Effects, errors and execution modes

Every effect in `libtmux.core` takes a Java ref from `:tmux/ref`; a captured map
is not an editable remote object. `new-session!`, `rename!`, `split-pane!` and
`refresh!` return new captured values when they can recapture the result. Void
operations return `nil`.

Validation errors include `:tmux/phase :validation` and
`:tmux/dispatch :not-dispatched`. Transport and mutation failures retain the
operation, dispatch certainty and whether effects are possible. Inspect the
stable subset with `core/error-summary`; do not parse exception messages.

`batch!` and `chain!` use fresh Java groups. Their per-position results can be
`:complete`, `:failed`, `:skipped` or `:unknown`; neither operation promises a
transaction or rollback. A group that can wait needs `{:kind :wait}` so its
release can reserve capacity.

### Bounded capture work and cancellation

`libtmux.async/runtime!` bounds asynchronous work. Submit each capture as a
task, then dereference its result after the runtime has admitted it. Task
submission does not add another tmux transport or alter the captured values.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.async :as async]
         '[libtmux.core :as tm])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})
              runtime (async/runtime! {:max-running 3})]
    (tm/new-session! server {:name "docs-parallel"})
    (let [tasks (mapv (fn [_] (async/submit! runtime #(tm/snapshot! server)))
                      (range 2))]
      (mapv #(boolean (some (fn [session]
                              (= "docs-parallel" (:session/name session)))
                            (:tmux/sessions @%)))
            tasks))))
;; => [true true]
```

`cancel!` is explicit. A cancellation accepted after a task starts reports
unknown dispatch certainty, because the enclosing tmux operation might have
started. Use a separate `:wait` task for operations that wait for a release.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.async :as async]
         '[libtmux.core :as tm])
(import '[java.time Duration])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})
              runtime (async/runtime! {})]
    (let [started (promise)
          task (async/submit! runtime {:kind :wait}
                              #(do (deliver started true)
                                   (tm/await-channel! server "docs-cancel"
                                      (Duration/ofSeconds 20))))]
      @started
      (async/cancel! task)
      (try
        @task
        (catch clojure.lang.ExceptionInfo failure
          [(:tmux/error (ex-data failure))
           (:tmux/dispatch (ex-data failure))])))))
;; => [:cancelled :unknown]
```

### Layouts and literal input

Creation and layout functions modify tmux and return fresh observations when
they create a target. `apply-layout!` accepts tmux's explicit layout text.
`send-literal!` writes characters without treating them as tmux key names;
`send-keys!` takes an explicit key vector. Their complete return does not mean
the program in the pane has finished processing input.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (let [session (tm/new-session! server {:name "docs-layout"})
          window (tm/new-window! (:tmux/ref session)
                                 {:name "docs-layout-window" :detached? true})
          pane (first (data/panes window))
          _ (tm/apply-layout! (:tmux/ref window) (:window/layout window))
          split (tm/split-pane! (:tmux/ref pane)
                                {:direction :right :percent 30})]
      [(:window/name window) (= (:tmux/link pane) (:tmux/link split))])))
;; => ["docs-layout-window" true]
```

<!-- clojure-snippet: fixture -->
```clojure
(require '[clojure.string :as str]
         '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (let [session (tm/new-session! server {:name "docs-literal"})
          pane (first (data/panes (first (data/windows session))))]
      (tm/send-literal! (:tmux/ref pane) "printf docs-literal")
      (tm/send-keys! (:tmux/ref pane) ["Enter"])
      (boolean (some #(str/includes? % "docs-literal")
                     (tm/capture! (:tmux/ref pane) {:from :history}))))))
;; => true
```

### Contextual targeting

A linked window keeps the same physical window identity but has another
occurrence context. Effects use its preserved ref, so an unlinked occurrence
does not silently target a remaining link in another session.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.core :as tm]
         '[libtmux.data :as data])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})]
    (let [first-session (tm/new-session! server {:name "docs-link-first"})
          second-session (tm/new-session! server {:name "docs-link-second"})
          window (first (data/windows first-session))]
      (tm/link-window! (:tmux/ref window) (:tmux/ref second-session))
      (let [linked (first (filter #(= (:window/id window) (:window/id %))
                                  (data/windows
                                   (tm/refresh! (:tmux/ref second-session)))))]
        [(data/same-entity? window linked) (data/same-link? window linked)]))))
;; => [true false]
```

## Lifecycle, streaming and optional adapters

`libtmux.async/runtime!` owns bounded JVM workers. Close it, or use `with-open`;
closing a borrowed server or executor is never an adapter side effect. Tasks
have explicit admission, cancellation and deadline results, and expose a Java
`CompletionStage` for Manifold or other JVM integration.

Control observations have an explicit owner and terminal result. A bounded
consumer can lose events; an upstream gap and adapter loss remain distinct.
Do not model a subscription as an unbounded lazy sequence, and do not perform
blocking observation work in a core.async `go` block. Add the optional
`io.github.libtmux/libtmux-clojure-core-async` dependency only for
`libtmux.core-async`.

An empty slow stream reports a timeout without becoming terminal. A later event
remains readable from the same owned stream.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.control :as control]
         '[libtmux.core :as tm])
(import '[java.lang AutoCloseable]
        '[java.time Duration])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path
                                :binary (System/getProperty
                                         "libtmux.docs.binary")})
              connection (control/attach!
                          (:tmux/ref (first (tm/sessions! server)))
                          (Duration/ofSeconds 2))
              stream (control/subscribe! connection)]
    (let [initial (control/next! stream (Duration/ofMillis 25))
          pane (first (:reply/lines
                       (control/send! connection
                                      ["new-window" "-P" "-F" "#{pane_id}"
                                       (str "stty -echo; printf docs-slow;"
                                            " read stop")])))
          event (control/next! stream (Duration/ofMillis 900))]
      [(= :timeout (:stream/status initial))
       (= pane (:pane/id event))])))
;; => [true true]
```

<!-- clojure-snippet: fixture -->
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
                          (Duration/ofSeconds 2))]
    (let [owner (adapter/observe! connection)
          pane (first (:reply/lines
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

### Manifold CompletionStage recipe

Manifold remains a consumer dependency. Add
`manifold/manifold {:mvn/version "0.5.0"}` to the application, then adapt the
public `CompletionStage`; it does not become a `libtmux` dependency. `then!`
uses the runtime's bounded callback executor. A Manifold deferred observes the
stage; cancelling that deferred does not cancel the task, so call `cancel!`
explicitly when ownership requires it.

<!-- clojure-snippet: fixture -->
```clojure
(require '[libtmux.async :as async]
         '[libtmux.core :as tm]
         '[manifold.deferred :as deferred])
(import '[java.util.concurrent CompletionStage])

(let [socket-path (java.nio.file.Path/of
                   (System/getProperty "libtmux.docs.socket")
                   (make-array String 0))]
  (with-open [server (tm/open! {:socket-path socket-path})
              runtime (async/runtime! {})]
    (tm/new-session! server {:name "docs-manifold"})
    (let [task (async/submit! runtime #(tm/snapshot! server))
          stage (async/then! runtime task
                             #(boolean (some (fn [session]
                                               (= "docs-manifold"
                                                  (:session/name session)))
                                             (:tmux/sessions %))))
          observed (deferred/->deferred stage)]
      [(instance? CompletionStage stage) @observed])))
;; => [true true]
```

## Compatibility and Java interop

The façade supports JDK 21 or newer and the repository's tmux 3.2a through 3.7c
matrix. It is JVM-only: it does not run in ClojureScript, Babashka or a native
image. Existing Java handles, `FilterExpr`, the optional Jackson codec and Java
async types remain directly usable when the Clojure functions do not cover an
operation.

[`clojure-reference.md`](clojure-reference.md) lists every captured key and
relation, its availability, identity meaning and Java filter support.
