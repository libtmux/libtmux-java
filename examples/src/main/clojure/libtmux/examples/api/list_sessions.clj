(ns libtmux.examples.api.list-sessions
  "List the sessions returned by one server capture."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (println (str "sessions="
                    (str/join "," (sort (map :session/name (tm/sessions! server)))))))))
