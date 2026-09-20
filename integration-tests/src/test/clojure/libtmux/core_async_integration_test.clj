(ns libtmux.core-async-integration-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [libtmux.control :as control]
            [libtmux.core :as tmux]
            [libtmux.core-async :as adapter]
            [libtmux.internal.fixture :as fixture])
  (:import [java.lang AutoCloseable]
           [java.time Duration]))

(defn- take-within [channel]
  (let [timeout (async/timeout 900)
        [value port] (async/alts!! [channel timeout] :priority true)]
    (if (identical? port timeout) ::timeout value)))

(defn- pane-event! [stream pane]
  (loop []
    (let [event (control/next! stream (Duration/ofMillis 900))]
      (if (= pane (:pane/id event)) event (recur)))))

(deftest raw-bytes-consumer-close-and-borrowed-connection-lifecycle
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (with-open [^AutoCloseable connection
                 (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable anchor (control/subscribe! connection)]
       (let [owner (adapter/observe! connection)
             pane (first (:reply/lines
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
