(ns libtmux.examples.api.connect
  "Connect to the private server supplied by run.sh."
  (:require [libtmux.core :as tm]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (let [snapshot (tm/snapshot! server)]
        (assert (seq (:tmux/sessions snapshot)))
        (println "connected=true")))))
