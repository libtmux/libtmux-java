(ns libtmux.examples.api.list-panes
  "Traverse session, window and pane observations without another acquisition."
  (:require [libtmux.core :as tm]
            [libtmux.data :as data]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
      (let [snapshot (tm/snapshot! server)
            session (data/exactly-one
                     (filter #(= "work-one" (:session/name %))
                             (:tmux/sessions snapshot)))
            window (data/exactly-one
                    (filter #(= "editor" (:window/name %)) (data/windows session)))]
        (println (str "panes=" (count (data/panes snapshot))))
        (println (str "window-panes=" (count (data/panes window))))))))
