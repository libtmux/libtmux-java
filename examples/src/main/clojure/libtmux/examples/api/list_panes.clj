(ns libtmux.examples.api.list-panes
  "Traverse session, window and pane observations without another acquisition."
  (:require [libtmux.core :as tm]
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
                           (:tmux/sessions snapshot)))
          window (data/exactly-one
                  (filter #(= "editor" (:window/name %)) (data/windows session)))]
      (println (str "panes=" (count (data/panes snapshot))))
      (println (str "window-panes=" (count (data/panes window)))))))
