(ns libtmux.core-async-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [libtmux.control :as control]
            [libtmux.core-async :as adapter])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- take-within [channel]
  (let [timeout (async/timeout 500)
        [value port] (async/alts!! [channel timeout] :priority true)]
    (if (identical? port timeout) ::timeout value)))

(deftest forwards-events-and-publishes-terminal-after-cleanup
  (let [calls (atom 0)
        closed (CountDownLatch. 1)]
    (with-redefs [control/subscribe! (fn [_ _] ::subscription)
                  control/next! (fn [_]
                                  (case (int (swap! calls inc))
                                    1 {:stream/status :timeout}
                                    2 {:stream/status :event :output/bytes (byte-array [1 2])
                                       :loss {:upstream/events 3 :upstream/bytes 4
                                              :adapter/events 0 :adapter/bytes 0}}
                                    {:stream/status :terminal
                                     :stream/terminal {:reason :control-exit}}))
                  control/close! (fn [_] (.countDown closed))]
      (let [owner (adapter/observe! ::connection)]
        (is (= [1 2] (vec (:output/bytes (take-within (adapter/events owner))))))
        (is (= :control-exit (:reason (take-within (adapter/terminal owner)))))
        (is (.await closed 500 TimeUnit/MILLISECONDS))
        (is (= {:events 1 :bytes 1048576} (adapter/limits owner)))))))

(deftest consumer-close-reports-adapter-stop-after-a-java-read-returns
  (let [entered (CountDownLatch. 1)
        released (CountDownLatch. 1)]
    (with-redefs [control/subscribe! (fn [_ _] ::subscription)
                  control/next! (fn [_]
                                  (.countDown entered)
                                  (.await released)
                                  {:stream/status :terminal
                                   :stream/terminal {:reason :closed}})
                  control/close! (fn [_] (.countDown released))]
      (let [owner (adapter/observe! ::connection)]
        (is (.await entered 500 TimeUnit/MILLISECONDS))
        (async/close! (adapter/events owner))
        (is (= :adapter-stopped (:reason (take-within (adapter/terminal owner)))))))))

(deftest consumer-close-stops-worker-blocked-on-event-put
  (let [read-count (atom 0)
        cleaned (CountDownLatch. 1)]
    (with-redefs [control/subscribe! (fn [_ _] ::subscription)
                  control/next! (fn [_]
                                  (swap! read-count inc)
                                  {:stream/status :event :output/bytes (byte-array [1])})
                  control/close! (fn [_] (.countDown cleaned))]
      (let [owner (adapter/observe! ::connection)]
        ;; No consumer receives the unbuffered event; close must win the alts!!.
        (async/close! (adapter/events owner))
        (is (= :adapter-stopped (:reason (take-within (adapter/terminal owner)))))
        (is (.await cleaned 500 TimeUnit/MILLISECONDS))
        (is (<= @read-count 1))))))

(deftest owner-stop-wins-a-blocked-event-put
  (let [offered (CountDownLatch. 1)]
    (with-redefs [control/subscribe! (fn [_ _] ::subscription)
                  control/next! (fn [_]
                                  (.countDown offered)
                                  {:stream/status :event :output/bytes (byte-array [1])})
                  control/close! (fn [_])]
      (let [owner (adapter/observe! ::connection)]
        (is (.await offered 500 TimeUnit/MILLISECONDS))
        (adapter/stop! owner)
        (is (= :adapter-stopped (:reason (take-within (adapter/terminal owner)))))))))

(deftest worker-failure-is-terminal-and-does-not-close-borrowed-connection
  (let [subscription-closes (atom 0)]
    (with-redefs [control/subscribe! (fn [_ _] ::subscription)
                  control/next! (fn [_] (throw (ex-info "boom" {:source :test})))
                  control/close! (fn [_] (swap! subscription-closes inc))]
      (let [owner (adapter/observe! ::borrowed)
            terminal (take-within (adapter/terminal owner))]
        (is (= :adapter-failure (:reason terminal)))
        (is (= :test (:source (ex-data (:cause terminal)))))
        (is (= 1 @subscription-closes))))))

(deftest validation-precedes-subscription
  (is (thrown? IllegalArgumentException (adapter/observe! ::connection {:event-capacity -1})))
  (is (thrown? IllegalArgumentException (adapter/observe! ::connection {:unknown true})))
  (is (thrown? IllegalArgumentException (adapter/stop! nil))))
