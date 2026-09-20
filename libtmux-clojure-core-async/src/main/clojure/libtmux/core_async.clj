(ns libtmux.core-async
  "Closeable core.async observation adapter for libtmux control streams."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as protocols]
            [libtmux.control :as control])
  (:import [java.lang AutoCloseable]))

(set! *warn-on-reflection* true)

(declare stop!)

(deftype ^:private EventsPort [channel stop-fn]
  protocols/ReadPort
  (take! [_ handler] (protocols/take! channel handler))
  protocols/Channel
  (close! [_]
    (stop-fn)
    (protocols/close! channel))
  (closed? [_] (protocols/closed? channel)))

(deftype Adapter [events terminal subscription stop-channel stopped worker limits]
  AutoCloseable
  (close [this] (stop! this)))

(defn events
  "Returns the read-only events channel. Closing it stops the adapter."
  [^Adapter adapter]
  (.-events adapter))

(defn terminal
  "Returns a buffered channel that delivers one outcome after cleanup."
  [^Adapter adapter]
  (.-terminal adapter))

(defn limits
  "Returns the adapter's exact retained-event and retained-byte bounds."
  [^Adapter adapter]
  (.-limits adapter))

(defn stop!
  "Stops observation and closes only the owned subscription and worker."
  [adapter]
  (when-not (instance? Adapter adapter)
    (throw (IllegalArgumentException. "stop! requires a core.async adapter")))
  (io!
   (when (compare-and-set! (.-stopped ^Adapter adapter) false true)
     (async/close! (.-stop-channel ^Adapter adapter))
     (control/close! (.-subscription ^Adapter adapter)))
   nil))

(defn- stopped-outcome []
  {:reason :adapter-stopped
   :cause nil
   :loss {:upstream/events 0 :upstream/bytes 0
          :adapter/events 0 :adapter/bytes 0}})

(defn- terminal-outcome [stopped result]
  (if @stopped
    (stopped-outcome)
    (case (:stream/status result)
      :terminal (:stream/terminal result)
      (stopped-outcome))))

(defn observe!
  "Starts an owned virtual worker over a borrowed control connection.

  `:event-capacity` defaults to zero. The worker can retain one pending event;
  a fixed buffer retains that many more. `:upstream-max-bytes` bounds each
  event through the Java subscription, so the adapter byte bound is
  `(inc event-capacity) * upstream-max-bytes`. Closing the events channel or
  adapter stops blocked reads and puts. The borrowed connection remains open."
  ([connection] (observe! connection {}))
  ([connection options]
   (when-not (map? options)
     (throw (IllegalArgumentException. "adapter options must be a map")))
   (let [allowed #{:event-capacity :upstream-capacity :upstream-max-bytes
                   :encoding :malformed :max-panes}
         unknown (seq (remove allowed (keys options)))
         {:keys [event-capacity upstream-capacity upstream-max-bytes encoding malformed max-panes]
          :or {event-capacity 0 upstream-capacity 256 upstream-max-bytes 1048576
               encoding :bytes malformed :report max-panes 1024}} options]
     (when (or unknown
               (not (and (integer? event-capacity) (<= 0 event-capacity Integer/MAX_VALUE)))
               (not (and (integer? upstream-capacity) (<= 1 upstream-capacity Integer/MAX_VALUE)))
               (not (and (integer? upstream-max-bytes) (<= 1 upstream-max-bytes Long/MAX_VALUE))))
       (throw (IllegalArgumentException. "invalid core.async adapter options")))
     (io!
      (let [subscription (control/subscribe! connection
                                             {:capacity upstream-capacity
                                              :max-bytes upstream-max-bytes
                                              :encoding encoding
                                              :malformed malformed
                                              :max-panes max-panes})
            raw-events (async/chan (when (pos? event-capacity)
                                     (async/buffer event-capacity)))
            terminal-channel (async/chan 1)
            stop-channel (async/chan)
            stopped (atom false)
            stop-fn #(when (compare-and-set! stopped false true)
                       (async/close! stop-channel)
                       (control/close! subscription))
            public-events (EventsPort. raw-events stop-fn)
            outcome (atom nil)
            worker (.start
                    (Thread/ofVirtual)
                    ^Runnable
                    (fn []
                      (try
                        (loop []
                          (let [result (control/next! subscription)]
                            (case (:stream/status result)
                              :timeout (recur)
                              :terminal (reset! outcome (terminal-outcome stopped result))
                              :event (let [[accepted port]
                                           (async/alts!! [[raw-events result] stop-channel]
                                                         :priority true)]
                                       (when (and (identical? port raw-events) accepted)
                                         (recur))))))
                        (catch Throwable failure
                          (reset! outcome
                                  {:reason :adapter-failure :cause failure
                                   :loss {:upstream/events 0 :upstream/bytes 0
                                          :adapter/events 0 :adapter/bytes 0}}))
                        (finally
                          (try (control/close! subscription)
                               (catch Throwable cleanup
                                 (if-let [^Throwable failure (:cause @outcome)]
                                   (.addSuppressed failure cleanup)
                                   (reset! outcome {:reason :cleanup-failure :cause cleanup}))))
                          (async/close! raw-events)
                          (async/>!! terminal-channel
                                     (or @outcome (terminal-outcome stopped {:stream/status :stopped})))
                          (async/close! terminal-channel)))))]
        (Adapter. public-events terminal-channel subscription stop-channel stopped worker
                  {:events (inc event-capacity)
                   :bytes (*' (inc event-capacity) upstream-max-bytes)}))))))
