(ns libtmux.examples.api.list-sessions
  "List the sessions returned by one server capture."
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
    (println (str "sessions="
                  (str/join "," (sort (map :session/name (tm/sessions! server))))))))
