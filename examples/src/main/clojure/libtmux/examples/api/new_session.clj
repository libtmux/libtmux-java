(ns libtmux.examples.api.new-session
  "Create a session running cat and inspect the replacement observation."
  (:require [libtmux.core :as tm]))

(defn -main [& _]
  (with-open [server (tm/start!)]
    (let [session (tm/new-session! server {:name "api-session" :command ["/bin/cat"]})]
      (assert (= "api-session" (:session/name session)))
      (println (str "created=" (:session/name session))))))
