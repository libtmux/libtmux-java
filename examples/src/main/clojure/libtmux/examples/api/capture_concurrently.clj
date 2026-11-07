(ns libtmux.examples.api.capture-concurrently
  "Capture on bounded workers and cancel an explicitly classified wait."
  (:require [libtmux.async :as async]
            [libtmux.core :as tm])
  (:import [java.lang AutoCloseable]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket :config-file config})
                ^AutoCloseable runtime (async/runtime! {:max-running 4 :queue-limit 4})]
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
        (println "cancelled=true cleaned=true")))))
