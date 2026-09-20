(ns libtmux.data-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [libtmux.data :as data])
  (:import [io.github.libtmux.query FilterExpr]
           [java.time Instant]))

(deftest cardinality-is-strict-and-bounded
  (is (nil? (data/one-or-none [])))
  (is (= false (data/exactly-one [false])))
  (is (nil? (data/exactly-one [nil])))
  (is (= :item (data/one-or-none [:item])))
  (doseq [[f values reason] [[data/exactly-one [] :none]
                            [data/exactly-one [1 2] :multiple]
                            [data/one-or-none [1 2] :multiple]]]
    (let [error (try (f values) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :tmux/cardinality (:tmux/error (ex-data error))))
      (is (= reason (:cardinality/reason (ex-data error))))))
  (let [observed (atom 0)
        items ((fn step [n]
                 (lazy-seq
                  (swap! observed inc)
                  (cons n (step (inc n))))) 0)]
    (is (thrown? clojure.lang.ExceptionInfo (data/one-or-none items)))
    (is (= 2 @observed))))

(deftest projection-is-edn-and-drops-references-recursively
  (let [at (Instant/parse "2026-09-20T00:00:00Z")
        value {:tmux/ref (Object.)
               :tmux/captured-at at
               :pane/floating? nil
               :pane/active? false
               :pane/current-path "raw\\xFF"
               :children [{:tmux/ref (Object.) :pane/id "%0"}]
               :tags #{:a :b}}
        projected (data/data value)]
    (is (= projected (edn/read-string (pr-str projected))))
    (is (= "2026-09-20T00:00:00Z" (:tmux/captured-at projected)))
    (is (false? (:pane/active? projected)))
    (is (contains? projected :pane/floating?))
    (is (not (contains? projected :pane/dead?)))
    (is (= "raw\\xFF" (:pane/current-path projected)))
    (is (= [{:pane/id "%0"}] (:children projected)))
    (is (not (contains? projected :tmux/ref))))
  (is (thrown? clojure.lang.ExceptionInfo (data/data (Object.)))))

(deftest physical-and-occurrence-identity-differ
  (let [entity {:tmux/realm "local" :tmux/server "opaque" :tmux/pid 12
                :tmux/kind :pane :tmux/id "%0"}
        first-link {:session/id "$0" :window/id "@0" :window/index 0}
        second-link (assoc first-link :window/index 1)
        a {:tmux/identity entity :tmux/link first-link}
        b {:tmux/identity entity :tmux/link second-link}]
    (is (data/same-entity? a b))
    (is (not (data/same-link? a b)))
    (is (data/same-link? a (assoc a :pane/title "renamed")))
    (is (not (data/same-entity? a (assoc-in b [:tmux/identity :tmux/pid] 13))))
    (is (not (data/same-entity? a (assoc-in b [:tmux/identity :tmux/server] "other"))))
    (is (thrown? clojure.lang.ExceptionInfo (data/entity-key {})))
    (is (thrown? clojure.lang.ExceptionInfo (data/link-key {:tmux/identity entity})))))

(deftest java-expression-adapters-follow-native-filter-arities
  (let [expression (FilterExpr/and [])
        a {:tmux/ref (Object.) :label :first}
        b {:tmux/ref (Object.) :label :second}
        entries [a b a]
        predicate (data/predicate expression)]
    (is (ifn? predicate))
    (is (true? (predicate a)))
    (is (thrown? clojure.lang.ExceptionInfo (predicate (data/data a))))
    (is (= entries (into [] (data/matching expression) entries)))
    (is (= entries (vec (data/matching expression entries))))
    (is (= [] (into [] (data/matching (FilterExpr/or [])) entries)))))
