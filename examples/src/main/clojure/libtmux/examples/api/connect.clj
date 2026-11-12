(ns libtmux.examples.api.connect
  "Connect to a private tmux server that the program starts and stops."
  (:require [libtmux.core :as tm]))

(defn -main [& _]
  (with-open [server (tm/start!)]
    (let [snapshot (tm/snapshot! server)]
      (assert (contains? snapshot :tmux/sessions))
      (println "connected=true"))))
