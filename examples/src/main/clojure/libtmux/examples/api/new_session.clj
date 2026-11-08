(ns libtmux.examples.api.new-session
  "Create a session running cat and inspect the replacement observation."
  (:require [libtmux.core :as tm]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (let [session (tm/new-session! server {:name "api-session" :command ["/bin/cat"]})]
        (assert (= "api-session" (:session/name session)))
        (println (str "created=" (:session/name session)))))))
