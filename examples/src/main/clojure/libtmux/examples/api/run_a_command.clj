(ns libtmux.examples.api.run-a-command
  "Use the preserved Java Pane ref to observe a command's real exit status."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data])
  (:import [io.github.libtmux Pane PaneRun PaneRun$Outcome]
           [java.time Duration]))

(defn -main [& _]
  (with-open [server (tm/start!)]
    (let [session (tm/new-session! server {:name "command-example" :command ["/bin/sh"]})
          ^Pane pane (:tmux/ref (data/exactly-one (data/panes session)))
          ^PaneRun result (.run pane "printf 'built\\n'; exit 3" (Duration/ofMillis 900))]
      (assert (= PaneRun$Outcome/FINISHED (.outcome result)))
      (assert (= 3 (.orElseThrow (.exitStatus result))))
      (assert (= ["built"] (vec (.output result))))
      (println (str "exit=" (.orElseThrow (.exitStatus result))
                    " output=" (str/join " / " (.output result)))))))
