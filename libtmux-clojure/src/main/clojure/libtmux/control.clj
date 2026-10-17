(ns libtmux.control
  "Explicit control replies and owned, bounded pane-output subscriptions.
  Control needs tmux 3.7 or newer; an older daemon is refused before
  any client is attached, with :tmux/error :tmux/unsupported-feature."
  (:require [libtmux.core :as tmux])
  (:import [io.github.libtmux Session Server]
           [io.github.libtmux.control ControlClient ControlReply Delivery$Event Delivery$Gap
            EventSubscription PaneOutput]
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
   (when tmux/*execution-check*
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
  when cancellation must not affect other requests and subscriptions."
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

;; One reader at a time: the Java subscription refuses overlapping reads, so reads queue on
;; read-lock and a timed read counts that wait against its budget.
(deftype ^:private OutputStream [^EventSubscription subscription identity options state read-lock]
  AutoCloseable
  (close [_] (io! (.close subscription))))
(alter-meta! #'->OutputStream assoc :private true)

(defn subscribe!
  "Creates a closeable output subscription borrowing its connection.
  :encoding is :bytes (caller-owned byte arrays) or :utf-8 (text decoded per
  pane by the Java client, so a character cut between two chunks arrives whole
  and a byte that is not UTF-8 is written \\xHH). :capacity bounds queued
  events; when full the oldest is dropped and the next read is a :gap.
  Readers compete; create separate subscriptions for independent fan-out."
  ([connection] (subscribe! connection {}))
  ([connection options]
   (when-not (and (instance? Connection connection) (map? options)
                  (every? #{:encoding :capacity} (keys options)))
     (invalid! :control/subscribe))
   (let [options (merge {:encoding :bytes :capacity 256} options)
         {:keys [encoding capacity]} options]
     (when-not (and (#{:bytes :utf-8} encoding) (positive? capacity Integer/MAX_VALUE))
       (invalid! :control/subscribe))
     (perform! :control/subscribe
               #(OutputStream.
                 (.subscribeOutput ^ControlClient (.-client ^Connection connection) (int capacity))
                 (.-identity ^Connection connection) options
                 (atom {:sequence 0}) (ReentrantLock.))))))

(defn- loss-data [^EventSubscription subscription]
  {:upstream/events (.droppedCount subscription)})

(defn terminal
  "Returns the single recorded stream outcome, or nil until a reader drains it.
  :reason is :closed after an intentional close, or :ended with the original
  :cause when the control client ended; the cause may contain sensitive
  information."
  [^OutputStream stream]
  (:terminal @(.-state stream)))

(defn- end! [^OutputStream stream]
  (let [^EventSubscription subscription (.-subscription stream)
        cause (.orElse (.cause subscription) nil)
        outcome (cond-> {:reason (if cause :ended :closed)
                         :loss (loss-data subscription)}
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
        ^EventSubscription subscription (.-subscription stream)]
    (if (instance? Delivery$Gap delivery)
      {:stream/status :gap
       :gap/events (.missed ^Delivery$Gap delivery)
       :loss (loss-data subscription)}
      (let [^PaneOutput output (.value ^Delivery$Event delivery)
            utf-8? (= :utf-8 (:encoding (.-options stream)))
            current (swap! state update :sequence inc)]
        {:stream/status :event
         :event/sequence (:sequence current)
         :tmux/identity (assoc (.-identity stream) :tmux/kind :pane :tmux/id (.value (.pane output)))
         :pane/id (.value (.pane output))
         (if utf-8? :output/text :output/bytes) (if utf-8? (.data output) (bytes-of output))
         :loss (loss-data subscription)}))))

(defn next!
  "Reads one event, gap, timeout, or terminal map; never uses nil for termination.
  A :gap names the events dropped since the previous read and precedes the
  events that remain. The optional nonnegative Duration includes waiting for
  another reader. Sequence numbers order the events of this stream. Close wakes
  a reader. Termination by the client keeps already delivered events readable;
  an intentional close discards them."
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
                                                   (.next subscription
                                                          (Duration/ofNanos
                                                           (max 0 (- budget (- (System/nanoTime) started)))))
                                                   (.next subscription))]
                          (cond
                            (.isPresent delivery) (delivery-data stream (.get delivery))
                            (.isClosed subscription) {:stream/status :terminal
                                                      :stream/terminal (end! stream)}
                            :else {:stream/status :timeout})))
                      (finally (.unlock lock)))))))))

(defn close!
  "Closes a connection or subscription. Closing a subscription leaves its
  borrowed connection open; closing a connection affects all its subscribers."
  [resource]
  (when-not (or (instance? Connection resource) (instance? OutputStream resource))
    (invalid! :control/close))
  (perform! :control/close #(.close ^AutoCloseable resource)))
