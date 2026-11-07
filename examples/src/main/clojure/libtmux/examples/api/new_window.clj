(ns libtmux.examples.api.new-window
  "Create a detached window, then refresh its parent session."
  (:require [clojure.string :as str]
            [libtmux.core :as tm]
            [libtmux.data :as data]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket
                                 :config-file config})]
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
                      (str/join "," (sort (map :window/name (data/windows after))))))))))
