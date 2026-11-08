(ns libtmux.examples.api.query
  "Compare a native predicate and Java expression on the same captured values."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data])
  (:import [io.github.libtmux Session_]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (let [sessions (tm/sessions! server)
            native (filterv #(= "work-one" (:session/name %)) sessions)
            expression (.is (Session_/name) "work-one")
            canonical (into [] (data/matching expression) sessions)]
        (assert (= native canonical))
        (assert (= "work-one" (:session/name (data/exactly-one canonical))))
        (println (str "matches=" (str/join "," (map :session/name native))))))))
