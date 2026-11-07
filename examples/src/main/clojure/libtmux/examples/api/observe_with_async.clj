(ns libtmux.examples.api.observe-with-async
  "Read bounded core.async events and close the consumer before draining."
  (:require [clojure.core.async :as channel]
            [clojure.string :as str]
            [libtmux.control :as control]
            [libtmux.core :as tm]
            [libtmux.core-async :as adapter]
            [libtmux.data :as data])
  (:import [java.lang AutoCloseable]
           [java.time Duration]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket :config-file config})]
      (let [session (tm/new-session! server {:name "async-example" :command ["/bin/sh"]})
            pane (data/exactly-one (data/panes session))
            marker "channel-ready"]
        (try
          (with-open [^AutoCloseable connection (control/attach! (:tmux/ref session) (Duration/ofMillis 900))]
            ;; Retain the default upstream bound for fragmented terminal output.
            (with-open [^AutoCloseable owner (adapter/observe! connection
                                                              {:event-capacity 1 :encoding :utf-8})]
              (let [deadline (channel/timeout 900)]
                (tm/send-literal! (:tmux/ref pane) "printf 'channel-%s\\n' ready")
                (tm/send-keys! (:tmux/ref pane) ["Enter"])
                (loop [suffix ""]
                  (let [[step port] (channel/alts!! [(adapter/events owner) deadline] :priority true)]
                    (when (or (identical? port deadline) (not= :event (:stream/status step)))
                      (throw (ex-info "Channel observation timed out, lost output or ended" {:step step})))
                    (let [text (if (= (:pane/id pane) (:pane/id step))
                                 (str suffix (:output/text step)) suffix)]
                      (when-not (str/includes? text marker)
                        (recur (subs text (max 0 (- (count text) (dec (count marker)))))))))))
              (channel/close! (adapter/events owner))
              (let [terminal-channel (adapter/terminal owner)
                    [outcome port] (channel/alts!! [terminal-channel (channel/timeout 900)] :priority true)]
                (assert (identical? terminal-channel port))
                (assert (= :adapter-stopped (:reason outcome)))))
            (assert (= :complete (:reply/status
                                 (control/send! connection ["display-message" "-p" "#{session_name}"]))))
            (println (str "observed=" marker))
            (println "adapter-stopped=true borrowed-open=true"))
          (catch clojure.lang.ExceptionInfo failure
            (if (= :tmux/unsupported-feature (:tmux/error (ex-data failure)))
              (do
                (assert (= :not-dispatched (:tmux/dispatch (ex-data failure))))
                (assert (empty? (tm/clients! server)))
                (println "requires tmux 3.7+"))
              (throw failure))))))))
