(ns libtmux.examples.api.query
  "Compare a native predicate and Java expression on the same captured values."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data])
  (:import [io.github.libtmux Session_]))

(defn -main [& _]
  (with-open [server (tm/start!)]
    (let [one (tm/new-session! server {:name "work-one" :window-name "editor"
                                       :command ["/bin/cat"]})]
      (tm/split-pane! (:tmux/ref (data/exactly-one (data/panes one)))
                      {:direction :right :command ["/bin/cat"]})
      (tm/new-window! (:tmux/ref one) {:name "logs" :detached? true :command ["/bin/cat"]})
      (tm/new-session! server {:name "work-two" :window-name "editor"
                               :command ["/bin/cat"]}))
    (let [sessions (tm/sessions! server)
          native (filterv #(= "work-one" (:session/name %)) sessions)
          expression (.is (Session_/name) "work-one")
          canonical (into [] (data/matching expression) sessions)]
      (assert (= native canonical))
      (assert (= "work-one" (:session/name (data/exactly-one canonical))))
      (println (str "matches=" (str/join "," (map :session/name native)))))))
