(ns libtmux.control
  "Explicit control replies through [[attach!]] and [[send!]].
  [[subscribe!]], [[next!]], and [[terminal]] expose owned bounded output streams.
  Control needs tmux 3.7 or newer; an older daemon is refused before
  any client is attached, with `:tmux/error` `:tmux/unsupported-feature`."
  (:require [libtmux.core :as tmux])
  (:import [io.github.libtmux PaneId Session Server]
           [io.github.libtmux.control ControlClient ControlReply Delivery$Event Delivery$Gap
            EventSubscription EventSubscription$Termination PaneOutput PaneOutputDecoder]
           [io.github.libtmux.exception DispatchException DispatchException$TimedOut
            LibTmuxException UnsupportedFeatureException]
           [java.lang AutoCloseable]
           [java.nio ByteBuffer]
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
   (when (and tmux/*execution-check* (not= :control/close operation))
     (tmux/*execution-check* {:operation operation :kind (if (= operation :control/next) :wait :mutation)}))
   (try (f)
        (catch LibTmuxException cause
          (throw (ex-info "Control operation failed"
                          {:tmux/error (cond
                                         (instance? UnsupportedFeatureException cause)
                                         :tmux/unsupported-feature
                                         (instance? DispatchException$TimedOut cause)
                                         :tmux/timeout
                                         :else :tmux/transport)
                           :tmux/operation operation :tmux/phase :protocol
                           :tmux/dispatch
                           (cond
                             (instance? UnsupportedFeatureException cause) :not-dispatched
                             (instance? DispatchException cause)
                             (case (.name (.outcome ^DispatchException cause))
                               "NOT_DISPATCHED" :not-dispatched
                               :unknown)
                             :else :unknown)} cause))))))

(deftype ^:private Connection [^ControlClient client identity]
  AutoCloseable
  (close [_] (io! (.close client))))
(alter-meta! #'->Connection assoc :private true)

(defn attach!
  "Attaches an owned control client to an explicit captured Session reference.
  Close releases this client, never its server. Use a dedicated connection
  when cancellation must not affect other requests and subscriptions.
  [[send!]] sends protocol argv; [[subscribe!]] observes output on this connection."
  ([session] (attach! session (Duration/ofSeconds 10)))
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
                (Connection. (.control ^Server (.server reference) reference timeout)
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
  send can end this connection and its other requests and subscriptions.
  [[libtmux.core/raw!]] uses process execution; [[subscribe!]] observes unsolicited output."
  ([connection argv] (send! connection argv (Duration/ofMillis 900)))
  ([connection argv timeout]
   (when-not (and (instance? Connection connection)
                  (vector? argv) (seq argv) (every? string? argv)
                  (duration? timeout) (not (.isZero ^Duration timeout)))
     (invalid! :control/send))
   (perform! :control/send
             #(reply-data (.send ^ControlClient (.-client ^Connection connection)
                                 ^java.util.List argv timeout)))))

;; One reader at a time: the Java subscription refuses overlapping reads, so reads queue on
;; read-lock and a timed read counts that wait against its budget.
(deftype ^:private OutputStream [^EventSubscription subscription identity options state read-lock]
  AutoCloseable
  (close [_] (io! (.close subscription))))
(alter-meta! #'->OutputStream assoc :private true)

(defn subscribe!
  "Creates a closeable output subscription borrowing its connection.
  `:encoding` is `:bytes` (caller-owned byte arrays) or `:utf-8` (incremental text
  per pane, reset after this subscription's gaps; malformed bytes use \\xHH).
  `:decoder-capacity` bounds partial-pane state (default 1024); an eviction is
  reported with `:decoder/reset` `:capacity` and a cumulative `:decoder/resets.`
  `:capacity` bounds queued events; `:max-bytes` bounds their unescaped payload
  (default 16 MiB).
  Oldest events are dropped until both bounds fit; an oversized event drops
  the queued prefix and itself. The next read is a `:gap` with byte/event loss.
  Readers compete; create separate subscriptions for independent fan-out.
  [[next!]] reads deliveries; [[terminal]] reports the drained end; [[close!]] stops locally."
  ([connection] (subscribe! connection {}))
  ([connection options]
   (when-not (and (instance? Connection connection) (map? options)
                  (every? #{:encoding :capacity :max-bytes :decoder-capacity} (keys options)))
     (invalid! :control/subscribe))
   (let [options (merge {:encoding :bytes :capacity 256 :max-bytes 16777216
                         :decoder-capacity 1024} options)
         {:keys [encoding capacity max-bytes decoder-capacity]} options]
     (when-not (and (#{:bytes :utf-8} encoding) (positive? capacity Integer/MAX_VALUE)
                    (positive? max-bytes Long/MAX_VALUE)
                    (positive? decoder-capacity Integer/MAX_VALUE))
       (invalid! :control/subscribe))
     (perform! :control/subscribe
               #(OutputStream.
                 (.subscribeOutput ^ControlClient (.-client ^Connection connection) (int capacity) (long max-bytes))
                 (.-identity ^Connection connection) options
                 (atom (cond-> {:sequence 0}
                         (= encoding :utf-8) (assoc :decoder (PaneOutputDecoder. (int decoder-capacity)))))
                 (ReentrantLock.))))))

(defn- loss-data [^EventSubscription subscription]
  {:upstream/events (.droppedCount subscription)
   :upstream/bytes (.droppedBytes subscription)})

(defn terminal
  "Returns the single recorded stream outcome, or `nil` until a reader drains it.
  `:reason` is `:closed`, `:completed`, `:control-exit`, `:transport-failure`,
  `:protocol-failure`, or `:unknown.` A control exit does not prove daemon death;
  `:cause` preserves the original exception and may contain sensitive content.
  Text streams include `:trailing-text` keyed by pane ID for
  incomplete final bytes rendered as \\xHH.
  [[next!]] records this outcome after buffered deliveries have drained."
  [^OutputStream stream]
  (:terminal @(.-state stream)))

(defn- end! [^OutputStream stream]
  (let [^EventSubscription subscription (.-subscription stream)
        ^EventSubscription$Termination ended (.orElseThrow (.termination subscription))
        cause (.orElse (.cause ended) nil)
        ^PaneOutputDecoder decoder (:decoder @(.-state stream))
        outcome (cond-> {:reason (case (.name (.reason ended))
                                  "CLOSED" :closed "COMPLETED" :completed
                                  "CONTROL_EXIT" :control-exit
                                  "TRANSPORT_FAILURE" :transport-failure
                                  "PROTOCOL_FAILURE" :protocol-failure
                                  :unknown)
                         :loss (loss-data subscription)}
                  decoder (assoc :trailing-text
                                 (into {} (map (fn [[pane text]] [(.value ^PaneId pane) text]))
                                       (.finish decoder))
                                 :decoder/resets (.resetCount decoder))
                  cause (assoc :cause cause))]
    (:terminal (swap! (.-state stream)
                      #(if (:terminal %) % (assoc % :terminal outcome))))))

(defn- bytes-of [^PaneOutput output]
  (let [^ByteBuffer buffer (.bytes output)
        octets (byte-array (.remaining buffer))]
    (.get buffer octets)
    octets))

(defn- delivery-data [^OutputStream stream delivery]
  (let [state (.-state stream)
        ^EventSubscription subscription (.-subscription stream)
        ^PaneOutputDecoder decoder (:decoder @state)]
    (if (instance? Delivery$Gap delivery)
      (do
        (when decoder (.reset decoder))
        (cond-> {:stream/status :gap
                 :gap/events (.missed ^Delivery$Gap delivery)
                 :loss (loss-data subscription)}
          decoder (assoc :decoder/reset :gap :decoder/resets (.resetCount decoder))))
      (let [^PaneOutput output (.value ^Delivery$Event delivery)
            before (when decoder (.resetCount decoder))
            payload (if decoder (.decode decoder output) (bytes-of output))
            current (swap! state update :sequence inc)]
        (cond-> {:stream/status :event
                 :event/sequence (:sequence current)
                 :tmux/identity (assoc (.-identity stream) :tmux/kind :pane :tmux/id (.value (.pane output)))
                 :pane/id (.value (.pane output))
                 (if decoder :output/text :output/bytes) payload
                 :loss (loss-data subscription)}
          decoder (assoc :decoder/resets (.resetCount decoder))
          (and decoder (> (.resetCount decoder) before)) (assoc :decoder/reset :capacity))))))

(defn- ^Optional read-delivery! [^EventSubscription subscription ^Duration timeout]
  (if timeout (.next subscription timeout) (.next subscription)))

(defn next!
  "Reads one event, gap, timeout, or terminal map; never uses `nil` for termination.
  A `:gap` names the events dropped since the previous read and precedes the
  events that remain. The optional nonnegative Duration includes waiting for
  another reader. Sequence numbers order the events of this stream. Close wakes
  a reader. Termination drains queued deliveries before the final outcome;
  intentionally closing this subscription discards its pending deliveries.
  [[subscribe!]] sets bounds; [[close!]] wakes a blocked reader."
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
                              ^Optional delivery (read-delivery!
                                                  subscription
                                                  (when budget
                                                    (Duration/ofNanos
                                                     (max 0 (- budget (- (System/nanoTime) started))))))]
                          (cond
                            (.isPresent delivery) (delivery-data stream (.get delivery))
                            (.isClosed subscription)
                            (let [^Optional pending (.poll subscription)]
                              (if (.isPresent pending)
                                (delivery-data stream (.get pending))
                                {:stream/status :terminal :stream/terminal (end! stream)}))
                            :else {:stream/status :timeout})))
                      (finally (.unlock lock)))))))))

(defn close!
  "Closes a connection or subscription. Closing a subscription leaves its
  borrowed connection open; closing a connection affects all its subscribers.
  Local closure remains available after [[libtmux.async/cancel!]].
  [[terminal]] inspects a drained subscription; [[attach!]] opens another connection."
  [resource]
  (when-not (or (instance? Connection resource) (instance? OutputStream resource))
    (invalid! :control/close))
  (perform! :control/close #(.close ^AutoCloseable resource)))
