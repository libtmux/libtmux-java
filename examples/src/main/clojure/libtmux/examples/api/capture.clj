(ns libtmux.examples.api.capture
  "Send literal shell input, wait for its output, and verify captured lines."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data])
  (:import [java.time Duration]))

(defn -main [& _]
  (with-open [server (tm/start!)]
    (let [session (tm/new-session! server {:name "api-capture" :command ["/bin/sh"]})
          pane (:tmux/ref (data/exactly-one (data/panes session)))]
      (doseq [word ["keys" "line"]]
        (tm/send-literal! pane (str "printf 'api-%s\\n' " word))
        (tm/send-keys! pane ["Enter"])
        (when-not (#{:appeared :present-at-entry}
                   (tm/await-text! pane (str "api-" word) (Duration/ofMillis 900)))
          (throw (IllegalStateException. "shell output did not arrive"))))
      (let [lines (set (map str/trim (tm/capture! pane)))]
        (doseq [expected ["api-keys" "api-line"]]
          (assert (contains? lines expected) (str "capture is missing " expected))
          (println expected))))))
