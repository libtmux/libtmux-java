(ns libtmux.examples.api.list-windows
  "Traverse root and session windows from the same captured graph."
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
    (let [snapshot (tm/snapshot! server)
          session (data/exactly-one
                   (filter #(= "work-one" (:session/name %))
                           (:tmux/sessions snapshot)))]
      (println (str "windows="
                    (str/join "," (sort (map :window/name (data/windows snapshot))))))
      (println (str "session-windows="
                    (str/join "," (sort (map :window/name (data/windows session)))))))))
