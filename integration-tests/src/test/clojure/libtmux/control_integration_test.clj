(ns libtmux.control-integration-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [libtmux.control :as control]
            [libtmux.core :as tmux]
            [libtmux.internal.fixture :as fixture])
  (:import [java.lang AutoCloseable ProcessHandle]
           [java.nio.file Files Path]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private ^Duration limit (Duration/ofMillis 900))

(defn- shell-quote [s] (str "'" (string/replace (str s) "'" "'\\''") "'"))

(defn- pane-event! [stream pane]
  (let [deadline (+ (System/nanoTime) (.toNanos limit))]
    (loop []
      (let [event (control/next! stream (Duration/ofNanos (max 0 (- deadline (System/nanoTime))))) ]
        (when-not (= :event (:stream/status event))
          (throw (ex-info "Expected pane output before terminal or timeout" event)))
        (if (= pane (:pane/id event)) event (recur))))))

(deftest protocol-reply-precedes-a-deferred-completion-marker
  (fixture/with-owned-server
   (fn [{:keys [server socket binary]}]
     (let [session (first (tmux/sessions! server))
           marker (.resolveSibling ^Path socket "completed")
           command (str (shell-quote binary) " -S " (shell-quote socket))]
       (try
         (with-open [^AutoCloseable connection (control/attach! (:tmux/ref session))]
           (let [reply (control/send! connection
                                     ["run-shell" (str command " wait-for release; : > "
                                                       (shell-quote marker) "; " command " wait-for -S finished")])]
             (is (= :complete (:reply/status reply)))
             (is (= :unverified (:operation/completion reply)))
             (is (not (Files/exists marker (make-array java.nio.file.LinkOption 0))))
             (tmux/raw! server ["wait-for" "-S" "release"])
             (tmux/raw! server ["wait-for" "finished"])
             (is (Files/exists marker (make-array java.nio.file.LinkOption 0)))
             (is (= ["aligned"] (:reply/lines (control/send! connection ["display-message" "-p" "aligned"]))))))
         (finally (Files/deleteIfExists marker)))))))

(deftest stream-preserves-split-utf8-and-independent-loss
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (with-open [^AutoCloseable connection (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable raw (control/subscribe! connection {:capacity 32})
                 ^AutoCloseable text (control/subscribe! connection {:encoding :utf-8 :malformed :replace})
                 ^AutoCloseable idle (control/subscribe! connection {:capacity 1 :max-bytes 1})]
       (let [reply (control/send! connection
                                  ["new-window" "-P" "-F" "#{pane_id}"
                                   "stty -echo; printf 'READY'; read -r start; printf '\\303'; read -r more; printf '\\251\\377'; read -r finish"])
             pane (first (:reply/lines reply))]
         (is (= "READY" (String. ^bytes (:output/bytes (pane-event! raw pane)) "UTF-8")))
         (is (= "READY" (:output/text (pane-event! text pane))))
         (control/send! connection ["send-keys" "-t" pane "Enter"])
         (is (= [195] (mapv #(bit-and 255 %) (:output/bytes (pane-event! raw pane)))))
         (is (= "" (:output/text (pane-event! text pane))))
         (control/send! connection ["send-keys" "-t" pane "Enter"])
         (is (= [169 255] (mapv #(bit-and 255 %) (:output/bytes (pane-event! raw pane)))))
         (let [event (pane-event! text pane)]
           (is (= "é�" (:output/text event)))
           (is (= 0 (get-in event [:loss :upstream/events])))
           (is (= pane (get-in event [:tmux/identity :tmux/id]))))
         (control/close! idle)
         (let [ended (:stream/terminal (control/next! idle limit))]
           (is (= :closed (:reason ended)))
           (is (pos? (get-in ended [:loss :upstream/events]))))
         (is (= :complete (:reply/status (control/send! connection ["display-message" "-p" "alive"])))))))))

(deftest close-wakes-reader-and-preserves-borrowed-connection
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (with-open [^AutoCloseable connection (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable stream (control/subscribe! connection)]
       (let [entered (CountDownLatch. 1)
             exited (CountDownLatch. 1)
             outcome (atom nil)
             worker (.start (Thread/ofVirtual)
                            ^Runnable (fn []
                                        (.countDown entered)
                                        (try (reset! outcome (control/next! stream))
                                             (finally (.countDown exited)))))]
         (is (.await entered 900 TimeUnit/MILLISECONDS))
         (control/close! stream)
         (is (.await exited 900 TimeUnit/MILLISECONDS))
         (.join worker 900)
         (is (not (.isAlive worker)))
         (is (= :closed (get-in @outcome [:stream/terminal :reason])))
         (is (= (control/terminal stream) (:stream/terminal @outcome)))
         (is (= :complete (:reply/status (control/send! connection ["display-message" "-p" "still-alive"])))))))))

(deftest stalled-subscription-enforces-event-and-byte-bounds
  (fixture/with-owned-server
   (fn [{:keys [server socket binary]}]
     (with-open [^AutoCloseable connection (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable anchor (control/subscribe! connection {:capacity 16 :max-bytes 64})
                 ^AutoCloseable stalled (control/subscribe! connection {:capacity 2 :max-bytes 4})]
       (let [tmux-command (str (shell-quote binary) " -S " (shell-quote socket))
             script (str "stty -echo; printf aa; " tmux-command " wait-for one; "
                         "printf bbb; " tmux-command " wait-for two; "
                         "printf cccc; " tmux-command " wait-for three; "
                         "printf 12345; " tmux-command " wait-for four; printf z; read stop")
             pane (first (:reply/lines
                          (control/send! connection ["new-window" "-P" "-F" "#{pane_id}" script])))]
         ;; Reading the anchor after every release proves each push reached the
         ;; control reader before the next one can be emitted.
         (is (= "aa" (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8")))
         (doseq [[gate expected] [["one" "bbb"] ["two" "cccc"]
                                  ["three" "12345"] ["four" "z"]]]
           (control/send! connection ["wait-for" "-S" gate])
           (is (= expected (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8"))))
         (let [retained (pane-event! stalled pane)]
           ;; Adding the oversized payload first evicts `cccc`, then drops
           ;; itself; only the final byte fits in the bounded buffer.
           (is (= "z" (String. ^bytes (:output/bytes retained) "UTF-8")))
           (is (= 4 (get-in retained [:loss :upstream/events])))
           (is (= 14 (get-in retained [:loss :upstream/bytes])))
           (is (= :timeout (:stream/status
                            (control/next! stalled (Duration/ofMillis 40)))))))))))

(deftest pane-death-does-not-end-session-output-stream
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (with-open [^AutoCloseable connection (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable stream (control/subscribe! connection)]
       (let [anchor (first (:reply/lines
                            (control/send! connection
                                           ["new-window" "-P" "-F" "#{pane_id}"
                                            "stty -echo; read -r start; printf alive; read -r stop"])))
             pane (first (:reply/lines
                          (control/send! connection
                                         ["new-window" "-P" "-F" "#{pane_id}"
                                          "stty -echo; printf gone; read -r stop"])))]
         (is (= "gone" (String. ^bytes (:output/bytes (pane-event! stream pane)) "UTF-8")))
         ;; Keep the source pane alive until its output arrives. A later
         ;; target lookup proves its removal before the anchor emits output.
         (is (= :complete (:reply/status (control/send! connection ["kill-window" "-t" pane]))))
         (is (= :failed (:reply/status (control/send! connection ["list-panes" "-t" pane]))))
         (is (= :complete (:reply/status (control/send! connection ["send-keys" "-t" anchor "Enter"]))))
         (is (= "alive" (String. ^bytes (:output/bytes (pane-event! stream anchor)) "UTF-8")))
         (is (= :complete (:reply/status
                           (control/send! connection ["display-message" "-p" "connection-alive"])))))))))

(deftest remote-server-loss-drains-buffer-and-preserves-terminal-cause
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (with-open [^AutoCloseable connection (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable anchor (control/subscribe! connection)
                 ^AutoCloseable buffered (control/subscribe! connection)]
       (let [pane (first (:reply/lines
                          (control/send! connection
                                         ["new-window" "-P" "-F" "#{pane_id}"
                                          "printf buffered; read stop"])))]
         (is (= "buffered" (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8")))
         ;; The anchor is an offer fence: the independent buffered subscriber
         ;; has received the same event before the daemon is stopped.
         (tmux/raw! server ["kill-server"])
         (is (= "buffered" (String. ^bytes (:output/bytes (pane-event! buffered pane)) "UTF-8")))
         (let [terminal (:stream/terminal (control/next! buffered limit))]
           (is (#{:control-exit :transport-failure} (:reason terminal)))
           (is (some? (:cause terminal)))
           (is (= terminal (control/terminal buffered)))))))))


(deftest closing-control-reclaims-only-its-authenticated-client
  (fixture/with-owned-server
   (fn [{:keys [server socket]}]
     (doseq [exceptional? [false true]]
       (let [owned (atom nil)
             expected (ex-info "leave connection scope" {})]
         (try
           (with-open [^AutoCloseable connection
                       (control/attach! (:tmux/ref (first (tmux/sessions! server))))]
             (let [pid (Long/parseLong ^String (first (:reply/lines
                                                      (control/send! connection
                                                                     ["display-message" "-p" "#{client_pid}"]))))
                   ^ProcessHandle process (.orElseThrow (ProcessHandle/of pid))
                   info (.info process)
                   parent (.orElseThrow (.parent process))
                   started (.orElseThrow (.startInstant info))
                   argv (vec (.orElse (.arguments info) (make-array String 0)))]
               (is (.isAlive process))
               (is (= (.pid (ProcessHandle/current)) (.pid ^ProcessHandle parent)))
               (is (some #{"-C"} argv))
               (is (some #{(str socket)} argv))
               (reset! owned {:process process :started started :argv argv
                              :exit (.onExit process)})
               (when exceptional? (throw expected))))
           (catch clojure.lang.ExceptionInfo failure
             (is (identical? expected failure))))
         (let [{:keys [^ProcessHandle process ^java.util.concurrent.CompletableFuture exit started]} @owned]
           (is (some? started))
           (.get exit 900 TimeUnit/MILLISECONDS)
           (is (not (.isAlive process)))
           (is (seq (tmux/sessions! server)))))))))

(deftest live-competing-readers-deliver-each-payload-once-and-share-termination
  (fixture/with-owned-server
   (fn [{:keys [server socket binary]}]
     (with-open [^AutoCloseable connection (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                 ^AutoCloseable anchor (control/subscribe! connection)
                 ^AutoCloseable shared (control/subscribe! connection)]
       (let [command (str (shell-quote binary) " -S " (shell-quote socket))
             script (str "printf aa; " command " wait-for chunk-two; "
                         "printf bb; " command " wait-for chunk-three; "
                         "printf cc; " command " wait-for chunk-four; "
                         "printf dd; read done")
             pane (first (:reply/lines
                          (control/send! connection ["new-window" "-P" "-F" "#{pane_id}" script])))
             entered (CountDownLatch. 2)
             start (CountDownLatch. 1)
             results [(promise) (promise)]
             workers (mapv (fn [result]
                             (Thread/startVirtualThread
                              ^Runnable
                              (fn []
                                (.countDown entered)
                                (deliver result
                                         (try
                                           (.await start)
                                           (loop [events []]
                                             (let [event (control/next! shared)]
                                               (if (= :terminal (:stream/status event))
                                                 {:events events :terminal (:stream/terminal event)}
                                                 (recur (if (= pane (:pane/id event))
                                                          (conj events event) events)))))
                                           (catch Exception failure failure))))))
                           results)]
         (try
           (is (.await entered 900 TimeUnit/MILLISECONDS))
           (.countDown start)
           (is (= "aa" (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8")))
           (doseq [[gate payload] [["chunk-two" "bb"] ["chunk-three" "cc"] ["chunk-four" "dd"]]]
             (control/send! connection ["wait-for" "-S" gate])
             (is (= payload (String. ^bytes (:output/bytes (pane-event! anchor pane)) "UTF-8"))))
           (tmux/raw! server ["kill-server"])
           (let [finished (mapv #(deref % 900 ::timeout) results)
                 events (sort-by :event/sequence (mapcat :events finished))
                 sequences (mapv :event/sequence events)]
             (is (every? map? finished))
             (is (= ["aa" "bb" "cc" "dd"]
                    (mapv #(String. ^bytes (:output/bytes %) "UTF-8") events)))
             (is (= 4 (count (set sequences))))
             (doseq [reader finished]
               (is (apply < 0 (map :event/sequence (:events reader)))))
             (is (= (:terminal (first finished)) (:terminal (second finished))))
             (is (= (control/terminal shared) (:terminal (first finished))))
             (is (#{:control-exit :transport-failure} (:reason (:terminal (first finished))))))
           (finally
             (.countDown start)
             (control/close! shared)
             (doseq [^Thread worker workers]
               (.interrupt worker)
               (.join worker 900)
               (is (not (.isAlive worker)))))))))))
