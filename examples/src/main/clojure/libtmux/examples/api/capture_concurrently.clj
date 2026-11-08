(ns libtmux.examples.api.capture-concurrently
  "Capture on bounded workers and cancel an explicitly classified wait."
  (:require [libtmux.async :as async]
            [libtmux.core :as tm]
            [libtmux.data :as data])
  (:import [java.lang AutoCloseable]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn -main [& _]
  (with-open [server (tm/start!)
              ^AutoCloseable runtime (async/runtime! {:max-running 4 :queue-limit 4})]
    (let [one (tm/new-session! server {:name "work-one" :window-name "editor"
                                       :command ["/bin/cat"]})]
      (tm/split-pane! (:tmux/ref (data/exactly-one (data/panes one)))
                      {:direction :right :command ["/bin/cat"]})
      (tm/new-window! (:tmux/ref one) {:name "logs" :detached? true :command ["/bin/cat"]})
      (tm/new-session! server {:name "work-two" :window-name "editor"
                               :command ["/bin/cat"]}))
    (let [panes (take 3 (tm/panes! server))
          tasks (mapv #(async/submit! runtime (fn [] (tm/capture! (:tmux/ref %)))) panes)
          captures (mapv deref tasks)
          started (CountDownLatch. 1)
          cleaned (promise)
          waiting (async/submit! runtime {:kind :wait}
                                 #(try
                                    (.countDown started)
                                    (tm/await-channel! server "example-cancel" (Duration/ofMillis 900))
                                    (finally (deliver cleaned true))))]
      (assert (= 3 (count captures)))
      (assert (every? vector? captures))
      (assert (.await started 900 TimeUnit/MILLISECONDS))
      (async/cancel! waiting)
      (let [failure (try @waiting nil (catch clojure.lang.ExceptionInfo error error))]
        (assert (= :cancelled (:tmux/error (ex-data failure))))
        (assert (realized? cleaned)))
      (println (str "captured=" (count captures)))
      (println "cancelled=true cleaned=true"))))
