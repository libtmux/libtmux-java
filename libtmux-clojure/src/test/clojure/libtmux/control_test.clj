(ns libtmux.control-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.control :as control])
  (:import [io.github.libtmux PaneId]
           [io.github.libtmux.batch OperationOutcome]
           [io.github.libtmux.control ControlReply EventSubscription
            EventSubscription$EndReason EventSubscription$Termination
            PaneOutputBytes PaneOutputDecoder]
           [java.lang AutoCloseable]
           [java.nio.charset CodingErrorAction]
           [java.time Duration]
           [java.util Optional]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.function Consumer ToLongFunction]))

(defn- test-stream [encoding malformed capacity max-bytes]
  (let [constructor (.getDeclaredConstructor EventSubscription
                                             (into-array Class [Integer/TYPE Long/TYPE
                                                                ToLongFunction Consumer]))
        _ (.setAccessible constructor true)
        subscription (.newInstance constructor
                                   (object-array [(int capacity) (long max-bytes)
                                                  (reify ToLongFunction
                                                    (applyAsLong [_ event]
                                                      (.size ^PaneOutputBytes event)))
                                                  (reify Consumer (accept [_ _]))]))]
    (libtmux.control.OutputStream.
     subscription (when (= :utf-8 encoding) (PaneOutputDecoder. malformed))
     {:tmux/endpoint :test} {:max-panes 2}
     (atom {:sequence 0 :panes #{} :decoder-resets 0 :loss 0}) (ReentrantLock.))))

(defn- offer! [^libtmux.control.OutputStream stream pane octets]
  (let [method (.getDeclaredMethod EventSubscription "offer" (into-array Class [Object]))]
    (.setAccessible method true)
    (.invoke method (.-subscription stream)
             (object-array [(PaneOutputBytes. (PaneId. pane)
                                               (byte-array (map unchecked-byte octets)))]))))

(defn- terminate! [^libtmux.control.OutputStream stream reason cause]
  (let [method (.getDeclaredMethod EventSubscription "end"
                                   (into-array Class [EventSubscription$Termination]))]
    (.setAccessible method true)
    (.invoke method (.-subscription stream)
             (object-array [(EventSubscription$Termination. reason (Optional/ofNullable cause))]))))

(deftest protocol-complete-does-not-claim-command-completion
  (doseq [[status expected] [[OperationOutcome/COMPLETE :complete]
                            [OperationOutcome/FAILED :failed]
                            [OperationOutcome/UNKNOWN :unknown]]]
    (is (= {:reply/status expected :reply/lines ["reply"]
            :operation/completion :unverified}
           (#'control/reply-data (ControlReply. status ["reply"]))))))

(deftest invalid-input-does-not-contact-tmux
  (doseq [action [#(control/attach! {}) #(control/send! nil ["list-panes"])
                  #(control/subscribe! nil) #(control/next! nil) #(control/close! nil)]]
    (let [failure (try (action) (catch clojure.lang.ExceptionInfo error error))]
      (is (= :tmux/validation (:tmux/error (ex-data failure))))
      (is (= :not-dispatched (:tmux/dispatch (ex-data failure)))))))

(deftest terminal-observers-see-the-complete-trailing-text-outcome
  (with-open [^libtmux.control.OutputStream stream
              (test-stream :utf-8 CodingErrorAction/REPLACE 2 8)]
    (offer! stream "%1" [195])
    (is (= "" (:output/text (control/next! stream Duration/ZERO))))
    (terminate! stream EventSubscription$EndReason/CONTROL_EXIT nil)
    (let [published (CountDownLatch. 1)
          release (CountDownLatch. 1)
          finished (CountDownLatch. 1)
          result (atom nil)
          state (.-state stream)]
      (add-watch state ::publication
                 (fn [_ _ previous current]
                   (when (and (nil? (:terminal previous)) (:terminal current))
                     (.countDown published)
                     (.await release))))
      (let [worker (.start (Thread/ofVirtual)
                           ^Runnable #(try (reset! result (control/next! stream Duration/ZERO))
                                           (finally (.countDown finished))))]
        (try
          (is (.await published 900 TimeUnit/MILLISECONDS))
          (let [observed (control/terminal stream)]
            (is (= [{:pane/id "%1" :output/text "�"}] (:output/trailing observed)))
            (.countDown release)
            (is (.await finished 900 TimeUnit/MILLISECONDS))
            (is (identical? observed (:stream/terminal @result)))
            (is (identical? observed (control/terminal stream))))
          (finally
            (.countDown release)
            (.join worker 900)
            (remove-watch state ::publication)))))))

(deftest upstream-gap-resets-incomplete-text-and-reports-the-reset
  (with-open [^AutoCloseable stream (test-stream :utf-8 CodingErrorAction/REPLACE 1 8)]
    (offer! stream "%1" [195])
    (is (= "" (:output/text (control/next! stream Duration/ZERO))))
    (offer! stream "%1" [88])
    (offer! stream "%1" [169])
    (let [event (control/next! stream Duration/ZERO)]
      (is (= "�" (:output/text event)))
      (is (= 1 (get-in event [:loss :upstream/events])))
      (is (= 1 (:decoder/resets event))))))

(deftest terminal-gap-reports-reset-without-inventing-trailing-text
  (with-open [^AutoCloseable stream (test-stream :utf-8 CodingErrorAction/REPLACE 1 1)]
    (offer! stream "%1" [195])
    (control/next! stream Duration/ZERO)
    (offer! stream "%1" [65 66])
    (terminate! stream EventSubscription$EndReason/CONTROL_EXIT nil)
    (let [outcome (:stream/terminal (control/next! stream Duration/ZERO))]
      (is (= [] (:output/trailing outcome)))
      (is (= 1 (:decoder/resets outcome)))
      (is (= 1 (get-in outcome [:loss :upstream/events]))))))

(deftest timed-read-includes-waiting-for-another-reader
  (with-open [^libtmux.control.OutputStream stream
              (test-stream :bytes CodingErrorAction/REPORT 2 8)]
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
  (with-open [^AutoCloseable stream (test-stream :bytes CodingErrorAction/REPORT 2 8)]
    (is (= :rejected (try (dosync (.close stream)) :closed
                         (catch IllegalStateException _ :rejected))))
    (is (= {:stream/status :timeout} (control/next! stream Duration/ZERO)))))

(deftest bounded-stream-reports-event-byte-and-oversize-loss
  (doseq [[capacity max-bytes chunks expected lost-events lost-bytes]
          [[2 8 [[65] [66] [67]] [66 67] 1 1]
           [8 2 [[65] [66] [67]] [66 67] 1 1]
           [8 2 [[65] [66 67 68] [69]] [69] 2 4]]]
    (with-open [^AutoCloseable stream
                (test-stream :bytes CodingErrorAction/REPORT capacity max-bytes)]
      (doseq [chunk chunks] (offer! stream "%1" chunk))
      (let [events (mapv (fn [_] (control/next! stream Duration/ZERO)) expected)]
        (is (= expected (mapv #(bit-and 255 (first (:output/bytes %))) events)))
        (is (every? #(= {:upstream/events lost-events :upstream/bytes lost-bytes
                         :adapter/events 0 :adapter/bytes 0} (:loss %)) events))
        (is (= {:stream/status :timeout} (control/next! stream Duration/ZERO)))))))

(deftest remote-termination-drains-buffer-and-preserves-original-cause
  (with-open [^AutoCloseable stream (test-stream :bytes CodingErrorAction/REPORT 2 8)]
    (let [cause (java.io.IOException. "control lost")]
      (offer! stream "%1" [65])
      (offer! stream "%1" [66])
      (terminate! stream EventSubscription$EndReason/TRANSPORT_FAILURE cause)
      (is (nil? (control/terminal stream)))
      (is (= [65 66] (mapv (fn [_]
                            (first (:output/bytes (control/next! stream Duration/ZERO))))
                          (range 2))))
      (let [outcome (:stream/terminal (control/next! stream Duration/ZERO))]
        (is (= :transport-failure (:reason outcome)))
        (is (identical? cause (:cause outcome)))
        (is (identical? outcome (:stream/terminal (control/next! stream Duration/ZERO))))))))

(deftest malformed-report-ends-with-a-stable-decode-cause
  (doseq [octets [[255] [195]]]
    (with-open [^AutoCloseable stream (test-stream :utf-8 CodingErrorAction/REPORT 2 8)]
      (offer! stream "%1" octets)
      (terminate! stream EventSubscription$EndReason/CONTROL_EXIT nil)
      (let [first-result (control/next! stream Duration/ZERO)
            result (if (= :event (:stream/status first-result))
                     (control/next! stream Duration/ZERO) first-result)
            outcome (:stream/terminal result)]
        (is (= :decode-failure (:reason outcome)))
        (is (instance? java.nio.charset.CharacterCodingException (:cause outcome)))
        (is (identical? outcome (control/terminal stream)))
        (is (identical? outcome (:stream/terminal (control/next! stream Duration/ZERO))))))))

(deftest competing-readers-deliver-each-event-once
  (with-open [^AutoCloseable stream (test-stream :bytes CodingErrorAction/REPORT 16 16)]
    (doseq [octet (range 16)] (offer! stream "%1" [octet]))
    (terminate! stream EventSubscription$EndReason/CONTROL_EXIT nil)
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
          (is (= (range 16) (map #(first (:output/bytes %)) events)))
          (is (= 2 (count outcomes)))
          (is (every? #(identical? (control/terminal stream) %) outcomes)))
        (finally
          (.countDown release)
          (control/close! stream)
          (doseq [^Thread worker workers] (.join worker 900)))))))
