(ns libtmux.examples.api.list-windows
  "Traverse root and session windows from the same captured graph."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (let [snapshot (tm/snapshot! server)
            session (data/exactly-one
                     (filter #(= "work-one" (:session/name %))
                             (:tmux/sessions snapshot)))]
        (println (str "windows="
                      (str/join "," (sort (map :window/name (data/windows snapshot))))))
        (println (str "session-windows="
                      (str/join "," (sort (map :window/name (data/windows session))))))))))
