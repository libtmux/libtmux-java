(ns libtmux.core-async-integration-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [libtmux.control :as control]
            [libtmux.core :as tmux]
            [libtmux.core-async :as adapter]
            [libtmux.internal.control-gate :as gate])
  (:import [java.lang AutoCloseable]
           [java.time Duration]))

(defn- take-within [channel]
  (let [timeout (async/timeout 900)
        [value port] (async/alts!! [channel timeout] :priority true)]
    (if (identical? port timeout) ::timeout value)))

(defn- pane-event! [stream pane]
  (loop []
    (let [event (control/next! stream (Duration/ofMillis 900))]
      (when-not (= :event (:stream/status event))
        (throw (ex-info "Expected pane output before stream end" event)))
      (if (= pane (:pane/id event)) event (recur)))))

(deftest raw-bytes-consumer-close-and-borrowed-connection-lifecycle
  (gate/with-control-server
   (fn [{:keys [server]}]
     (tmux/raw! server ["respawn-pane" "-k" "-t"
                       (:pane/id (first (tmux/panes! server))) "exec cat"])
     (with-open [^AutoCloseable connection
                 (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable owner (adapter/observe! connection)
                 ^AutoCloseable anchor (control/subscribe! connection)]
       (let [pane (first (:reply/lines
                          (control/send! connection
                                         ["new-window" "-P" "-F" "#{pane_id}"
                                          "stty -echo; printf raw-bytes; read more; printf blocked; read stop"])))
             event (take-within (adapter/events owner))]
         (is (= pane (:pane/id event)))
         (is (= (mapv int (.getBytes "raw-bytes" "UTF-8"))
                (mapv #(bit-and 255 %) (:output/bytes event))))
         (is (= (mapv int (.getBytes "raw-bytes" "UTF-8"))
                (mapv #(bit-and 255 %) (:output/bytes (pane-event! anchor pane)))))
         (control/send! connection ["send-keys" "-t" pane "Enter"])
         ;; The independent anchor proves the second push reached every Java
         ;; subscription before the adapter consumer closes its channel.
         (is (= "blocked"
                (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8")))
         (async/close! (adapter/events owner))
         (is (= :adapter-stopped (:reason (take-within (adapter/terminal owner)))))
         (is (= :complete
                (:reply/status
                 (control/send! connection ["display-message" "-p" "borrowed-alive"])))))))))

(deftest stop-preserves-incomplete-text-from-the-owned-subscription
  (gate/with-control-server
   (fn [{:keys [server]}]
     (tmux/raw! server ["respawn-pane" "-k" "-t"
                       (:pane/id (first (tmux/panes! server))) "exec cat"])
     (with-open [^AutoCloseable connection
                 (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable owner (adapter/observe! connection {:encoding :utf-8})]
       (let [pane (first (:reply/lines
                          (control/send! connection ["new-window" "-P" "-F" "#{pane_id}"
                                                     "stty -echo; printf '\\303'; read stop"])))
             event (take-within (adapter/events owner))]
         (is (= pane (:pane/id event)))
         (is (= "" (:output/text event)))
         (adapter/stop! owner)
         (let [outcome (take-within (adapter/terminal owner))]
           (is (= :adapter-stopped (:reason outcome)))
           (is (= {pane "\\xc3"} (get-in outcome [:upstream/terminal :trailing-text])))
           (is (= {:upstream/events 0 :upstream/bytes 0 :adapter/events 0} (:loss outcome))))
         (is (= :complete (:reply/status
                           (control/send! connection ["display-message" "-p" "still-borrowed"])))))))))

(deftest stop-after-remote-end-preserves-the-cause-without-draining-output
  (gate/with-control-server
   (fn [{:keys [server]}]
     (tmux/raw! server ["respawn-pane" "-k" "-t"
                       (:pane/id (first (tmux/panes! server))) "exec cat"])
     (with-open [^AutoCloseable connection
                 (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable owner (adapter/observe! connection {:upstream-capacity 2})
                 ^AutoCloseable anchor (control/subscribe! connection)]
       (let [pane (first (:reply/lines
                          (control/send! connection ["new-window" "-P" "-F" "#{pane_id}"
                                                     (str "stty -echo; printf first; read more;"
                                                          " printf second; read stop")]))) ]
         (is (= "first" (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8")))
         (control/send! connection ["send-keys" "-t" pane "Enter"])
         (is (= "second" (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8")))
         (tmux/raw! server ["kill-server"])
         (is (= :terminal (:stream/status (control/next! anchor (Duration/ofMillis 900)))))
         (adapter/stop! owner)
         (let [outcome (take-within (adapter/terminal owner))]
           (is (= :adapter-stopped (:reason outcome)))
           (is (= :control-exit (get-in outcome [:upstream/terminal :reason])))
           (is (some? (get-in outcome [:upstream/terminal :cause])))))))))
