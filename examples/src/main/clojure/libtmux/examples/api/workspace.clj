(ns libtmux.examples.api.workspace
  "Build a layout, rename a window, and follow its new placement after a move."
  (:require [libtmux.core :as tm]
            [libtmux.data :as data]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (let [source (tm/new-session! server {:name "workspace" :window-name "editor"
                                           :command ["/bin/cat"]})
            editor (data/exactly-one (data/windows source))
            pane (data/exactly-one (data/panes editor))
            destination (tm/new-session! server {:name "destination" :command ["/bin/cat"]})]
        (tm/new-window! (:tmux/ref source) {:name "logs" :detached? true :command ["/bin/cat"]})
        (tm/split-pane! (:tmux/ref pane) {:direction :right :percent 40 :command ["/bin/cat"]})
        (let [layout (:window/layout (tm/refresh! (:tmux/ref editor)))]
          (tm/apply-layout! (:tmux/ref editor) layout))
        (let [renamed (tm/rename! (:tmux/ref editor) "code")]
          (assert (= "editor" (:window/name editor)))
          (assert (= "code" (:window/name renamed)))
          (tm/move-window! (:tmux/ref renamed) (:tmux/ref destination) 5))
        (let [moved (data/exactly-one
                     (filter #(= (:window/id editor) (:window/id %)) (tm/windows! server)))]
          (assert (data/same-entity? editor moved))
          (assert (not (data/same-link? editor moved)))
          (assert (= (:session/id destination) (get-in moved [:tmux/link :session/id])))
          (assert (= 5 (:window/index moved)))
          (assert (= 2 (count (data/panes moved))))
          (println (str "window=" (:window/name moved) " panes=" (count (data/panes moved))))
          (println "same-entity=true same-placement=false"))))))
