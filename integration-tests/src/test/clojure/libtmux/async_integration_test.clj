(ns libtmux.async-integration-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.async :as async]
            [libtmux.core :as core]
            [libtmux.internal.fixture :as fixture])
  (:import [io.github.libtmux Server WakeReason]
           [java.time Duration]
           [java.util UUID]))

(deftest reserved-wait-does-not-head-block-its-release
  (fixture/with-owned-server
    (fn [{:keys [^Server server]}]
      (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime! {:max-running 2})]
        (let [channel (.channel server (str "clj-async-" (UUID/randomUUID)))
              waiting (async/submit! runtime {:kind :wait}
                                     #(.awaitReservingCapacity channel
                                                               (Duration/ofMillis 800)))
              release (async/submit! runtime #(.signal channel))]
          (is (nil? (deref release 500 ::timeout)))
          (is (= WakeReason/SIGNALLED (deref waiting 900 ::timeout))))))))


(deftest saturated-waiting-groups-retain-runtime-and-transport-release-capacity
  (fixture/with-owned-server
   (fn [{:keys [^Server server]}]
     (with-open [transport (io.github.libtmux.transport.ProcessTransport. 2)
                 shared (Server/using (.config server) transport)]
       (doseq [group [core/batch! core/chain!]]
         (with-open [^libtmux.async.AsyncRuntime runtime
                     (async/runtime! {:max-running 2 :queue-limit 1})]
           (let [channel (str "group-release-" (UUID/randomUUID))
                 started (str channel "-started")
                 waiting (async/submit! runtime {:kind :wait}
                                        #(group shared [["wait-for" "-S" started]
                                                        ["wait-for" channel]]
                                                {:kind :wait}))]
             (try
               (is (= :signalled (core/await-channel! server started (Duration/ofMillis 800))))
               (let [queued (async/submit! runtime {:kind :wait}
                                           #(group shared [["display-message" "-p" "queued-result"]]
                                                   {:kind :wait}))
                     excess (async/submit! runtime {:kind :wait}
                                           #(throw (AssertionError. "excess waiting work ran")))]
                 (is (= :queued (async/task-state queued)))
                 (is (= {:status :busy :kind :wait} excess))
                 (let [release (async/submit! runtime #(core/signal-channel! shared channel))]
                   (is (not (map? release)) "ordinary release lost its reserved admission")
                   (when-not (map? release)
                    (is (nil? (deref release 800 ::timeout)))
                   (is (= [:complete :complete]
                          (mapv :operation/outcome (deref waiting 800 ::timeout))))
                   (let [result (deref queued 800 ::timeout)]
                     (is (= [:complete] (mapv :operation/outcome result)))
                     (is (= [["queued-result"]] (mapv :operation/stdout result))))
                   (is (= [:terminal :terminal :terminal]
                          (mapv async/task-state [waiting queued release]))))))
               (finally
                 (core/signal-channel! server channel)
                 (async/cancel! waiting))))))))))

(deftest cancelled-async-groups-retain-positional-data-and-transport-cause
  (fixture/with-owned-server
   (fn [{:keys [^Server server]}]
     (doseq [group [core/batch! core/chain!]]
       (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime! {:max-running 2})]
         (let [id (str "async-group-cancel-" (UUID/randomUUID))
               ready (str id "-ready")
               release (str id "-release")
               waiting (async/submit! runtime {:kind :wait}
                                      #(group server [["set-buffer" "-b" id "applied"]
                                                      ["wait-for" "-S" ready]
                                                      ["wait-for" release]
                                                      ["set-buffer" "-b" (str id "-after") "uncertain"]]
                                              {:kind :wait}))]
           (try
             (is (= :signalled (core/await-channel! server ready (Duration/ofMillis 800))))
             (is (true? (async/cancel! waiting)))
             (let [failure (try (deref waiting 800 ::timeout) (catch Exception e e))]
               (is (= :cancelled (:tmux/error (ex-data failure))))
               (is (= :unknown (:tmux/dispatch (ex-data failure))))
               (is (= [:unknown :unknown :unknown :unknown]
                      (mapv :operation/outcome (:tmux/results (ex-data failure)))))
               (is (and (instance? Throwable failure)
                        (some #(instance? io.github.libtmux.transport.TmuxTransportException %)
                              (take-while some? (iterate #(.getCause ^Throwable %) failure)))))
               (is (= :terminal (async/task-state waiting))))
             (is (= "applied" (core/show-buffer! server id)))
             (finally
               (core/signal-channel! server release)
               (async/cancel! waiting)))))))))
