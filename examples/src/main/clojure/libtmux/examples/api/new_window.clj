(ns libtmux.examples.api.new-window
  "Create a detached window, then refresh its parent session."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data]))

(defn -main [& _]
  (with-open [server (tm/start!)]
    (let [one (tm/new-session! server {:name "work-one" :window-name "editor"
                                       :command ["/bin/cat"]})]
      (tm/split-pane! (:tmux/ref (data/exactly-one (data/panes one)))
                      {:direction :right :command ["/bin/cat"]})
      (tm/new-window! (:tmux/ref one) {:name "logs" :detached? true :command ["/bin/cat"]})
      (tm/new-session! server {:name "work-two" :window-name "editor"
                               :command ["/bin/cat"]}))
    (let [before (data/exactly-one
                  (filter #(= "work-one" (:session/name %)) (tm/sessions! server)))
          window (tm/new-window! (:tmux/ref before)
                                 {:name "api-window" :command ["/bin/cat"]
                                  :detached? true})
          after (tm/refresh! (:tmux/ref before))]
      (assert (= 2 (count (data/windows before))))
      (assert (= 3 (count (data/windows after))))
      (println (str "created=" (:window/name window)))
      (println (str "windows="
                    (str/join "," (sort (map :window/name (data/windows after)))))))))
