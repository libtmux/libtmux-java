(ns libtmux.core-async
  "Closeable core.async observation through [[observe!]].
  [[events]] carries deliveries; [[terminal]] reports local subscription cleanup.
  Borrow an explicit connection from [[libtmux.control/attach!]]."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as protocols]
            [libtmux.control :as control])
  (:import [java.lang AutoCloseable]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(declare stop!)

(deftype ^:private EventsPort [channel stop-fn]
  protocols/ReadPort
  (take! [_ handler] (protocols/take! channel handler))
  protocols/Channel
  (close! [_]
    (io!
     (stop-fn)
     (protocols/close! channel)))
  (closed? [_] (protocols/closed? channel)))

(alter-meta! #'->EventsPort assoc :private true)

(deftype ^:private Adapter [events terminal subscription stop-channel stopped worker limits]
  AutoCloseable
  (close [this] (stop! this)))

(alter-meta! #'->Adapter assoc :private true)

(defn events
  "Returns the read-only events channel. Closing it stops the adapter.
  Created by [[observe!]]; [[terminal]] delivers the final cleanup outcome."
  [^Adapter adapter]
  (.-events adapter))

(defn terminal
  "Returns a buffered channel that delivers one outcome after cleanup.
  [[events]] carries deliveries; [[stop!]] requests local shutdown."
  [^Adapter adapter]
  (.-terminal adapter))

(defn limits
  "Returns the adapter's exact retained-event bound.
  [[observe!]] defines upstream and adapter capacity separately."
  [^Adapter adapter]
  (.-limits adapter))

(defn stop!
  "Stops observation and closes only the owned subscription and worker.
  [[terminal]] reports completion; the connection supplied to [[observe!]] stays open."
  [adapter]
  (when-not (instance? Adapter adapter)
    (throw (IllegalArgumentException. "stop! requires a core.async adapter")))
  (io!
   (when (compare-and-set! (.-stopped ^Adapter adapter) false true)
     (async/close! (.-stop-channel ^Adapter adapter))
     (control/close! (.-subscription ^Adapter adapter)))
   nil))

(defn- stopped-outcome [upstream]
  {:reason :adapter-stopped
   :cause nil
   :upstream/terminal upstream
   :loss (assoc (:loss upstream) :adapter/events 0)})

(defn- terminal-outcome [stopped result]
  (if @stopped
    (stopped-outcome (:stream/terminal result))
    (update (:stream/terminal result) :loss assoc :adapter/events 0)))

(defn- closed-terminal! [subscription]
  (let [step (control/next! subscription Duration/ZERO)
        step (if (= :gap (:stream/status step))
               (control/next! subscription Duration/ZERO) step)]
    (when-not (= :terminal (:stream/status step))
      (throw (IllegalStateException. "Closed subscription did not provide a terminal outcome")))
    step))

(defn observe!
  "Starts an owned virtual worker over a borrowed control connection.

  `:event-capacity` defaults to zero. The worker can retain one pending event;
  a fixed buffer retains that many more. `:upstream-capacity` bounds the Java
  subscription, which reports overflow as a `:gap` value ahead of the events that
  remain; `:encoding` is `:bytes` or `:utf-8.` Closing the events channel or
  adapter stops blocked reads and puts. A stopped outcome retains the final
  `:upstream/terminal`, including loss, cause and trailing text. Adapter loss
  stays separate under `:adapter/events.` The borrowed connection remains open.
  Borrow a connection from [[libtmux.control/attach!]]; read [[events]] and [[terminal]]."
  ([connection] (observe! connection {}))
  ([connection options]
   (when-not (map? options)
     (throw (IllegalArgumentException. "adapter options must be a map")))
   (let [allowed #{:event-capacity :upstream-capacity :encoding}
         unknown (seq (remove allowed (keys options)))
         {:keys [event-capacity upstream-capacity encoding]
          :or {event-capacity 0 upstream-capacity 256 encoding :bytes}} options]
     (when (or unknown
               (not (and (integer? event-capacity) (<= 0 event-capacity Integer/MAX_VALUE)))
               (not (and (integer? upstream-capacity) (<= 1 upstream-capacity Integer/MAX_VALUE))))
       (throw (IllegalArgumentException. "invalid core.async adapter options")))
     (io!
      (let [subscription (control/subscribe! connection
                                             {:capacity upstream-capacity
                                              :encoding encoding})
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
                              (:event :gap) (let [[accepted port]
                                           (async/alts!! [[raw-events result] stop-channel]
                                                         :priority true)]
                                       (when (and (identical? port raw-events) accepted)
                                         (recur))))))
                        (catch Throwable failure
                          (reset! outcome
                                  {:reason :adapter-failure :cause failure
                                   :loss {:adapter/events 0}}))
                        (finally
                          (try
                            (control/close! subscription)
                            (when-not @outcome
                              (reset! outcome (terminal-outcome stopped (closed-terminal! subscription))))
                            (catch Throwable cleanup
                              (if-let [^Throwable failure (:cause @outcome)]
                                (.addSuppressed failure cleanup)
                                (reset! outcome {:reason :cleanup-failure :cause cleanup}))))
                          (async/close! raw-events)
                          (async/>!! terminal-channel @outcome)
                          (async/close! terminal-channel)))))]
        (Adapter. public-events terminal-channel subscription stop-channel stopped worker
                  {:events (inc event-capacity)}))))))
