(ns libtmux.control
  "Explicit control replies and owned, bounded pane-output subscriptions."
  (:require [libtmux.core :as tmux])
  (:import [io.github.libtmux Session Server PaneId LibTmuxException]
           [io.github.libtmux.control ControlClient ControlReply EventSubscription
            EventSubscription$Delivery EventSubscription$Termination PaneOutputBytes PaneOutputDecoder]
           [io.github.libtmux.transport TmuxTransportException TmuxTimeoutException]
           [java.lang AutoCloseable]
           [java.nio.charset CharacterCodingException CodingErrorAction]
           [java.time Duration]
           [java.util Optional]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(set! *warn-on-reflection* true)

(defn- invalid! [operation]
  (throw (ex-info "Invalid control operation arguments"
                  {:tmux/error :tmux/validation :tmux/operation operation
                   :tmux/phase :validation :tmux/dispatch :not-dispatched})))

(defn- positive? [value limit]
  (and (integer? value) (<= 1 value limit)))

(defn- duration? [value]
  (and (instance? Duration value) (not (.isNegative ^Duration value))))

(defn- perform! [operation f]
  (io!
   (when tmux/*execution-check*
     (tmux/*execution-check* {:operation operation :kind (if (= operation :control/next) :wait :mutation)}))
   (try (f)
        (catch LibTmuxException cause
          (throw (ex-info "Control operation failed"
                          {:tmux/error (if (instance? TmuxTimeoutException cause)
                                         :tmux/timeout :tmux/transport)
                           :tmux/operation operation :tmux/phase :protocol
                           :tmux/dispatch
                           (if (instance? TmuxTransportException cause)
                             (case (.name (.outcome ^TmuxTransportException cause))
                               "NOT_DISPATCHED" :not-dispatched
                               :unknown)
                             :unknown)} cause))))))

(deftype ^:private Connection [^ControlClient client identity]
  AutoCloseable
  (close [_] (io! (.close client))))
(alter-meta! #'->Connection assoc :private true)

(defn attach!
  "Attaches an owned control client to an explicit captured Session reference.
  Close releases this client, never its server. Use a dedicated connection
  when cancellation must not affect other requests and subscriptions."
  ([session] (attach! session (Duration/ofMillis 900)))
  ([session timeout]
   (when-not (and (instance? Session session) (duration? timeout)
                  (not (.isZero ^Duration timeout)))
     (invalid! :control/attach))
   (perform! :control/attach
             #(let [captured (tmux/refresh! session)
                    ^Session reference (:tmux/ref captured)]
                (when-not reference
                  (throw (ex-info "Control session no longer exists"
                                  {:tmux/error :tmux/not-found
                                   :tmux/operation :control/attach
                                   :tmux/phase :validation :tmux/dispatch :not-dispatched})))
                (Connection. (ControlClient/attach (.config (.server reference))
                                                  (.id reference) timeout)
                             (dissoc (:tmux/identity captured) :tmux/kind :tmux/id))))))

(defn- reply-data [^ControlReply reply]
  {:reply/status (case (.name (.outcome reply))
                   "COMPLETE" :complete "FAILED" :failed
                   "SKIPPED" :skipped :unknown)
   :reply/lines (vec (.lines reply))
   :operation/completion :unverified})

(defn send!
  "Sends argv and returns protocol status, lines, and unverified completion.
  A complete reply does not prove deferred work finished. Cancelling an active
  send can end this connection and its other requests and subscriptions."
  ([connection argv] (send! connection argv (Duration/ofMillis 900)))
  ([connection argv timeout]
   (when-not (and (instance? Connection connection)
                  (vector? argv) (seq argv) (every? string? argv)
                  (duration? timeout) (not (.isZero ^Duration timeout)))
     (invalid! :control/send))
   (perform! :control/send
             #(reply-data (.send ^ControlClient (.-client ^Connection connection)
                                 ^java.util.List argv timeout)))))

(deftype ^:private OutputStream [^EventSubscription subscription
                                 ^PaneOutputDecoder decoder identity options state read-lock]
  AutoCloseable
  (close [_] (io! (.close subscription))))
(alter-meta! #'->OutputStream assoc :private true)

(defn subscribe!
  "Creates a closeable output subscription borrowing its connection.
  :encoding is :bytes (caller-owned byte arrays) or :utf-8. Text uses explicit
  :malformed :report or :replace and resets after loss. :capacity, :max-bytes,
  and :max-panes bound queued events, bytes, and text decoder identities.
  Readers compete; create separate subscriptions for independent fan-out."
  ([connection] (subscribe! connection {}))
  ([connection options]
   (when-not (and (instance? Connection connection) (map? options)
                  (every? #{:encoding :malformed :capacity :max-bytes :max-panes} (keys options)))
     (invalid! :control/subscribe))
   (let [options (merge {:encoding :bytes :malformed :report :capacity 256
                         :max-bytes 1048576 :max-panes 1024} options)
         {:keys [encoding malformed capacity max-bytes max-panes]} options]
     (when-not (and (#{:bytes :utf-8} encoding) (#{:report :replace} malformed)
                    (positive? capacity Integer/MAX_VALUE)
                    (positive? max-bytes Long/MAX_VALUE)
                    (positive? max-panes Integer/MAX_VALUE))
       (invalid! :control/subscribe))
     (perform! :control/subscribe
               #(OutputStream.
                 (.subscribeOutputBytes ^ControlClient (.-client ^Connection connection) (int capacity) (long max-bytes))
                 (when (= :utf-8 encoding)
                   (PaneOutputDecoder. (if (= :report malformed)
                                         CodingErrorAction/REPORT CodingErrorAction/REPLACE)))
                 (.-identity ^Connection connection) options
                 (atom {:sequence 0 :panes #{} :decoder-resets 0 :loss 0}) (ReentrantLock.))))))

(defn- loss-data [^EventSubscription subscription]
  {:upstream/events (.droppedCount subscription)
   :upstream/bytes (.droppedBytes subscription)
   :adapter/events 0 :adapter/bytes 0})

(defn- reason [^EventSubscription$Termination ended]
  (case (.name (.reason ended))
    "CLOSED" :closed "CONTROL_EXIT" :control-exit
    "TRANSPORT_FAILURE" :transport-failure "PROTOCOL_FAILURE" :protocol-failure :unknown))

(defn terminal
  "Returns the single recorded stream outcome, or nil until a reader drains it.
  :control-exit means the client ended; it does not assert daemon death.
  The original :cause, when present, may contain sensitive information."
  [^OutputStream stream]
  (:terminal @(.-state stream)))

(defn- end!
  ([stream failure] (end! stream failure nil))
  ([^OutputStream stream failure trailing]
   (let [^EventSubscription subscription (.-subscription stream)
         ^EventSubscription$Termination ended (.orElse (.termination subscription) nil)
         outcome (cond-> {:reason (if failure :decode-failure (if ended (reason ended) :unknown))
                          :cause (or failure (when ended (.orElse (.cause ended) nil)))
                          :loss (loss-data subscription)
                          :decoder/resets (:decoder-resets @(.-state stream))}
                   trailing (assoc :output/trailing trailing))]
     (:terminal (swap! (.-state stream)
                       #(if (:terminal %) % (assoc % :terminal outcome)))))))

(defn- finish! [^OutputStream stream]
  (let [^PaneOutputDecoder decoder (.-decoder stream)
        subscription (.-subscription stream)
        upstream (.orElse (.termination ^EventSubscription subscription) nil)
        intentional? (and upstream (= :closed (reason upstream)))]
    (if (or (nil? decoder) intentional?)
      (do (when decoder (.reset decoder)) (end! stream nil))
      (try
        (when (> (.droppedCount ^EventSubscription subscription) (:loss @(.-state stream)))
          (.reset decoder)
          (swap! (.-state stream) #(-> % (assoc :panes #{}) (update :decoder-resets inc))))
        (let [trailing (into []
                             (keep (fn [^PaneId pane]
                                     (let [text (.data (.finish decoder pane))]
                                       (when (seq text)
                                         {:pane/id (.value pane) :output/text text}))))
                             (:panes @(.-state stream)))]
          (end! stream nil trailing))
        (catch CharacterCodingException failure (end! stream failure))))))

(defn- event-data [^OutputStream stream ^EventSubscription$Delivery delivery]
  (let [^PaneOutputBytes event (.event delivery)
        ^PaneOutputDecoder decoder (.-decoder stream)
        pane (.pane event)
        state (.-state stream)
        loss (.droppedCount delivery)
        reset? (and decoder (not (contains? (:panes @state) pane))
                    (>= (count (:panes @state)) (:max-panes (.-options stream))))]
    (when (and decoder (or reset? (> loss (:loss @state))))
      (.reset decoder)
      (swap! state #(-> % (assoc :panes #{}) (update :decoder-resets inc))))
    (let [value (if decoder (.data (.decode decoder delivery)) (.data event))
          current (swap! state #(cond-> (-> % (update :sequence inc) (assoc :loss loss))
                                  decoder (update :panes conj pane)))]
      {:stream/status :event
       :event/sequence (:sequence current)
       :tmux/identity (assoc (.-identity stream) :tmux/kind :pane :tmux/id (.value pane))
       :pane/id (.value pane)
       (if decoder :output/text :output/bytes) value
       :loss {:upstream/events loss :upstream/bytes (.droppedBytes delivery)
              :adapter/events 0 :adapter/bytes 0}
       :decoder/resets (:decoder-resets current)})))

(defn next!
  "Reads one event, timeout, or terminal map; never uses nil for termination.
  The optional nonnegative Duration includes waiting for another reader.
  Sequence numbers order deliveries from this stream. Close wakes a reader.
  Remote termination drains buffered bytes and reports trailing text in the
  terminal outcome; intentional close discards buffered output."
  ([stream] (next! stream nil))
  ([stream timeout]
   (when-not (and (instance? OutputStream stream) (or (nil? timeout) (duration? timeout)))
     (invalid! :control/next))
   (let [^OutputStream stream stream]
     (perform! :control/next
               #(let [^ReentrantLock lock (.-read-lock stream)
                      started (System/nanoTime)
                      budget (when timeout
                               (try (.toNanos ^Duration timeout)
                                    (catch ArithmeticException _ Long/MAX_VALUE)))
                      acquired (if budget (.tryLock lock budget TimeUnit/NANOSECONDS)
                                   (do (.lockInterruptibly lock) true))]
                  (if-not acquired
                    {:stream/status :timeout}
                    (try
                   (if-let [outcome (terminal stream)]
                    {:stream/status :terminal :stream/terminal outcome}
                    (let [^EventSubscription subscription (.-subscription stream)
                          ^Optional delivery (if budget
                                     (.nextDelivery subscription
                                                    (Duration/ofNanos
                                                     (max 0 (- budget (- (System/nanoTime) started)))))
                                     (.nextDelivery subscription))]
                      (if (.isPresent delivery)
                        (try (event-data stream (.get delivery))
                             (catch CharacterCodingException failure
                               (.close subscription)
                               {:stream/status :terminal :stream/terminal (end! stream failure)}))
                        (if (.isClosed subscription)
                          {:stream/status :terminal :stream/terminal (finish! stream)}
                          {:stream/status :timeout}))))
                   (finally (.unlock lock)))))))))

(defn close!
  "Closes a connection or subscription. Closing a subscription leaves its
  borrowed connection open; closing a connection affects all its subscribers."
  [resource]
  (when-not (or (instance? Connection resource) (instance? OutputStream resource))
    (invalid! :control/close))
  (perform! :control/close #(.close ^AutoCloseable resource)))
