(ns libtmux.control-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.control :as control]
            [libtmux.core :as core])
  (:import [io.github.libtmux PaneId]
           [io.github.libtmux.batch OperationOutcome]
           [io.github.libtmux.control ControlReply EventSubscription PaneOutput PaneOutputDecoder]
           [java.io IOException]
           [java.lang AutoCloseable]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.function Consumer ToLongFunction]))

(defn- test-stream
  ([encoding capacity] (test-stream encoding capacity 16777216))
  ([encoding capacity max-bytes]
  (let [constructor (.getDeclaredConstructor EventSubscription
                                             (into-array Class [Integer/TYPE Long/TYPE ToLongFunction Consumer]))
        _ (.setAccessible constructor true)
        subscription (.newInstance constructor
                                   (object-array [(int capacity) (long max-bytes)
                                                  (reify ToLongFunction
                                                    (applyAsLong [_ output]
                                                      (.remaining (.bytes ^PaneOutput output))))
                                                  (reify Consumer (accept [_ _]))]))]
    (libtmux.control.OutputStream.
     subscription {:tmux/endpoint :test} {:encoding encoding :capacity capacity}
     (atom (cond-> {:sequence 0}
             (= encoding :utf-8) (assoc :decoder (PaneOutputDecoder. 1)))) (ReentrantLock.)))))

(defn- offer-bytes! [^libtmux.control.OutputStream stream pane octets]
  (let [method (.getDeclaredMethod EventSubscription "offer" (into-array Class [Object]))]
    (.setAccessible method true)
    (.invoke method (.-subscription stream)
             (object-array [(PaneOutput. (PaneId. pane) "unrelated predecoded text"
                                         (ByteBuffer/wrap (byte-array (map unchecked-byte octets))))]))))

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

(deftest local-close-remains-available-during-cancellation
  (with-open [^libtmux.control.OutputStream stream (test-stream :bytes 1)]
    (let [^EventSubscription subscription (.-subscription stream)]
      (is (thrown? IllegalStateException (dosync (control/close! stream))))
      (is (false? (.isClosed subscription)))
      (let [result (binding [core/*execution-check* (fn [_] (throw (ex-info "cancelled" {:tmux/error :cancelled})))]
                     (try (control/close! stream) :closed
                          (catch clojure.lang.ExceptionInfo failure failure)))]
        (is (= :closed result)))
      (is (.isClosed subscription)))))

(deftest timed-read-drains-output-that-arrives-at-termination
  (with-open [^libtmux.control.OutputStream stream (test-stream :bytes 8)]
    (let [cause (IOException. "transport ended")
          reader #'libtmux.control/read-delivery!
          read-fn @reader]
      (with-redefs-fn
        {reader (fn [subscription timeout]
                  (let [delivery (read-fn subscription timeout)]
                    (offer! stream "%1" "last")
                    (end! stream cause)
                    delivery))}
        #(let [step (control/next! stream Duration/ZERO)]
           (is (= :event (:stream/status step)))
           (is (= [108 97 115 116] (vec (:output/bytes step))))))
      (let [step (control/next! stream Duration/ZERO)]
        (is (= :terminal (:stream/status step)))
        (is (identical? cause (get-in step [:stream/terminal :cause]))))
      (is (.isEmpty (.poll ^EventSubscription (.-subscription stream)))))))

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
      (is (= {:upstream/events 2 :upstream/bytes 2} (:loss gap)))
      (is (= "c" (:output/text event)))
      (is (= {:upstream/events 2 :upstream/bytes 2} (:loss event)))
      (is (= {:stream/status :timeout} (control/next! stream Duration/ZERO))))))

(deftest byte-bound-reports-the-exact-loss-before-the-retained-suffix
  (with-open [^AutoCloseable stream (test-stream :bytes 8 4)]
    (doseq [text ["aa" "bbb" "cccc" "12345" "z"]] (offer! stream "%1" text))
    (let [gap (control/next! stream Duration/ZERO)
          event (control/next! stream Duration/ZERO)]
      (is (= :gap (:stream/status gap)))
      (is (= 4 (:gap/events gap)))
      (is (= {:upstream/events 4 :upstream/bytes 14} (:loss gap)))
      (is (= [122] (vec (:output/bytes event))))
      (is (= (:loss gap) (:loss event)))
      (is (= {:stream/status :timeout} (control/next! stream Duration/ZERO))))))

(deftest text-is-decoded-after-this-subscribers-gap
  (with-open [^AutoCloseable stream (test-stream :utf-8 1)]
    (offer-bytes! stream "%1" [195])
    (is (= "" (:output/text (control/next! stream Duration/ZERO))))
    (offer-bytes! stream "%2" [33])
    (offer-bytes! stream "%1" [169 255])
    (let [gap (control/next! stream Duration/ZERO)
          event (control/next! stream Duration/ZERO)]
      (is (= :gap (:stream/status gap)))
      (is (= :gap (:decoder/reset gap)))
      (is (= 1 (:decoder/resets gap)))
      (is (= "\\xa9\\xff" (:output/text event)))
      (is (= 1 (:decoder/resets event))))))

(deftest decoder-eviction-is-visible-and-terminal-flush-is-published-once
  (with-open [^AutoCloseable stream (test-stream :utf-8 4)]
    (offer-bytes! stream "%1" [195])
    (is (= "" (:output/text (control/next! stream Duration/ZERO))))
    (offer-bytes! stream "%2" [195])
    (let [event (control/next! stream Duration/ZERO)]
      (is (= :capacity (:decoder/reset event)))
      (is (= 1 (:decoder/resets event))))
    (control/close! stream)
    (let [outcome (:stream/terminal (control/next! stream Duration/ZERO))]
      (is (= {"%2" "\\xc3"} (:trailing-text outcome)))
      (is (= 1 (:decoder/resets outcome)))
      (is (identical? outcome (control/terminal stream)))
      (is (identical? outcome (:stream/terminal (control/next! stream Duration/ZERO)))))))

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
        (is (= :unknown (:reason outcome)))
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
  (with-open [^AutoCloseable stream (test-stream :utf-8 16)]
    (offer-bytes! stream "%2" [195])
    (is (= "" (:output/text (control/next! stream Duration/ZERO))))
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
          (is (= (range 2 18) (map :event/sequence events)))
          (is (= (map #(str (char %)) (range 65 81)) (map :output/text events)))
          (is (= 2 (count outcomes)))
          (is (= {"%2" "\\xc3"} (:trailing-text (first outcomes))))
          (is (every? #(identical? (control/terminal stream) %) outcomes)))
        (finally
          (.countDown release)
          (control/close! stream)
          (doseq [^Thread worker workers] (.join worker 900)))))))
