(ns libtmux.control-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.control :as control])
  (:import [io.github.libtmux PaneId]
           [io.github.libtmux.batch OperationOutcome]
           [io.github.libtmux.control ControlReply EventSubscription PaneOutput]
           [java.lang AutoCloseable]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.function Consumer]))

(defn- test-stream [encoding capacity]
  (let [constructor (.getDeclaredConstructor EventSubscription
                                             (into-array Class [Integer/TYPE Consumer]))
        _ (.setAccessible constructor true)
        subscription (.newInstance constructor
                                   (object-array [(int capacity) (reify Consumer (accept [_ _]))]))]
    (libtmux.control.OutputStream.
     subscription {:tmux/endpoint :test} {:encoding encoding :capacity capacity}
     (atom {:sequence 0}) (ReentrantLock.))))

(defn- offer! [^libtmux.control.OutputStream stream pane text]
  (let [method (.getDeclaredMethod EventSubscription "offer" (into-array Class [Object]))]
    (.setAccessible method true)
    (.invoke method (.-subscription stream)
             (object-array [(PaneOutput. (PaneId. pane) text
                                         (ByteBuffer/wrap (.getBytes ^String text "UTF-8")))]))))

(defn- end! [^libtmux.control.OutputStream stream cause]
  (let [method (.getDeclaredMethod EventSubscription "end" (into-array Class [Throwable]))]
    (.setAccessible method true)
    (.invoke method (.-subscription stream) (object-array [cause]))))

(deftest protocol-complete-does-not-claim-command-completion
  (doseq [[status expected] [[OperationOutcome/COMPLETE :complete]
                            [OperationOutcome/FAILED :failed]
                            [OperationOutcome/UNKNOWN :unknown]]]
    (is (= {:reply/status expected :reply/lines ["reply"]
            :operation/completion :unverified}
           (#'control/reply-data (ControlReply. status ["reply"]))))))

(deftest invalid-input-does-not-contact-tmux
  (doseq [action [#(control/attach! {}) #(control/send! nil ["list-panes"])
                  #(control/subscribe! nil) #(control/next! nil) #(control/close! nil)
                  #(control/subscribe! nil {:max-bytes 1})]]
    (let [failure (try (action) (catch clojure.lang.ExceptionInfo error error))]
      (is (= :tmux/validation (:tmux/error (ex-data failure))))
      (is (= :not-dispatched (:tmux/dispatch (ex-data failure)))))))

(deftest events-carry-bytes-or-text-by-encoding
  (with-open [^AutoCloseable raw (test-stream :bytes 4)
              ^AutoCloseable text (test-stream :utf-8 4)]
    (offer! raw "%1" "é")
    (offer! text "%1" "é")
    (let [event (control/next! raw Duration/ZERO)]
      (is (= :event (:stream/status event)))
      (is (= [-61 -87] (vec (:output/bytes event))))
      (is (= "%1" (:pane/id event)))
      (is (= 1 (:event/sequence event))))
    (is (= "é" (:output/text (control/next! text Duration/ZERO))))))

(deftest overflow-is-a-gap-ahead-of-the-events-that-remain
  (with-open [^AutoCloseable stream (test-stream :utf-8 1)]
    (offer! stream "%1" "a")
    (offer! stream "%1" "b")
    (offer! stream "%1" "c")
    (let [gap (control/next! stream Duration/ZERO)
          event (control/next! stream Duration/ZERO)]
      (is (= :gap (:stream/status gap)))
      (is (= 2 (:gap/events gap)))
      (is (= {:upstream/events 2} (:loss gap)))
      (is (= "c" (:output/text event)))
      (is (= {:upstream/events 2} (:loss event)))
      (is (= {:stream/status :timeout} (control/next! stream Duration/ZERO))))))

(deftest timed-read-includes-waiting-for-another-reader
  (with-open [^libtmux.control.OutputStream stream (test-stream :bytes 2)]
    (let [^ReentrantLock lock (.-read-lock stream)
          finished (CountDownLatch. 1)
          outcome (atom nil)]
      (.lock lock)
      (let [worker (.start (Thread/ofVirtual)
                           ^Runnable #(try (reset! outcome (control/next! stream Duration/ZERO))
                                           (finally (.countDown finished))))]
        (try
          (is (.await finished 900 TimeUnit/MILLISECONDS))
          (finally (.unlock lock) (.join worker 900)))
        (is (= {:stream/status :timeout} @outcome))))))

(deftest closeable-stream-rejects-retriable-transaction-effects
  (with-open [^AutoCloseable stream (test-stream :bytes 2)]
    (is (= :rejected (try (dosync (.close stream)) :closed
                         (catch IllegalStateException _ :rejected))))
    (is (= {:stream/status :timeout} (control/next! stream Duration/ZERO)))))

(deftest client-termination-drains-buffer-and-preserves-original-cause
  (with-open [^AutoCloseable stream (test-stream :bytes 2)]
    (let [cause (java.io.IOException. "control lost")]
      (offer! stream "%1" "A")
      (offer! stream "%1" "B")
      (end! stream cause)
      (is (nil? (control/terminal stream)))
      (is (= [65 66] (mapv (fn [_] (first (:output/bytes (control/next! stream Duration/ZERO))))
                           (range 2))))
      (let [outcome (:stream/terminal (control/next! stream Duration/ZERO))]
        (is (= :ended (:reason outcome)))
        (is (identical? cause (:cause outcome)))
        (is (identical? outcome (:stream/terminal (control/next! stream Duration/ZERO))))))))

(deftest intentional-close-discards-the-buffer-and-reports-closed
  (with-open [^AutoCloseable stream (test-stream :bytes 2)]
    (offer! stream "%1" "A")
    (control/close! stream)
    (let [outcome (:stream/terminal (control/next! stream Duration/ZERO))]
      (is (= :closed (:reason outcome)))
      (is (not (contains? outcome :cause))))))

(deftest competing-readers-deliver-each-event-once
  (with-open [^AutoCloseable stream (test-stream :bytes 16)]
    (doseq [octet (range 16)] (offer! stream "%1" (str (char (+ 65 octet)))))
    (end! stream (java.io.IOException. "done"))
    (let [ready (CountDownLatch. 2)
          release (CountDownLatch. 1)
          finished (CountDownLatch. 2)
          results (atom [])
          read-events (fn []
                        (.countDown ready)
                        (.await release)
                        (try
                          (loop []
                            (let [result (control/next! stream)]
                              (swap! results conj result)
                              (when (= :event (:stream/status result)) (recur))))
                          (finally (.countDown finished))))
          workers (mapv (fn [_] (.start (Thread/ofVirtual) ^Runnable read-events)) (range 2))]
      (try
        (is (.await ready 900 TimeUnit/MILLISECONDS))
        (.countDown release)
        (is (.await finished 900 TimeUnit/MILLISECONDS))
        (let [events (sort-by :event/sequence (filter #(= :event (:stream/status %)) @results))
              outcomes (map :stream/terminal (filter #(= :terminal (:stream/status %)) @results))]
          (is (= (range 1 17) (map :event/sequence events)))
          (is (= (range 65 81) (map #(first (:output/bytes %)) events)))
          (is (= 2 (count outcomes)))
          (is (every? #(identical? (control/terminal stream) %) outcomes)))
        (finally
          (.countDown release)
          (control/close! stream)
          (doseq [^Thread worker workers] (.join worker 900)))))))
