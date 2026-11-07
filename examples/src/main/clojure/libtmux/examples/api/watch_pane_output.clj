(ns libtmux.examples.api.watch-pane-output
  "Observe bounded UTF-8 output on an owned virtual worker, then close locally."
  (:require [clojure.string :as str]
            [libtmux.async :as async]
            [libtmux.control :as control]
            [libtmux.core :as tm]
            [libtmux.data :as data])
  (:import [java.lang AutoCloseable]
           [java.time Duration]))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (IllegalArgumentException. "expected: tmux-binary socket-path config-file")))
  (let [[binary socket config] args]
    (with-open [server (tm/open! {:binary binary :socket-path socket :config-file config})]
      (let [session (tm/new-session! server {:name "stream-example" :command ["/bin/sh"]})
            pane (data/exactly-one (data/panes session))
            marker "stream-ready"]
        (try
          (with-open [^AutoCloseable runtime (async/runtime! {:max-running 2 :queue-limit 2})
                      ^AutoCloseable connection (control/attach! (:tmux/ref session) (Duration/ofMillis 900))
                      ^AutoCloseable stream (control/subscribe! connection
                                                               {:encoding :utf-8 :capacity 8 :max-bytes 8192})]
            (let [observed
                  (async/submit! runtime {:kind :wait :timeout-ms 900}
                                 #(loop [suffix ""]
                                    (let [step (control/next! stream)]
                                      (when-not (= :event (:stream/status step))
                                        (throw (ex-info "Observation lost output or ended" step)))
                                      (if (= (:pane/id pane) (:pane/id step))
                                        (let [text (str suffix (:output/text step))]
                                          (if (str/includes? text marker)
                                            marker
                                            (recur (subs text (max 0 (- (count text) (dec (count marker))))))))
                                        (recur suffix)))))]
              (tm/send-literal! (:tmux/ref pane) "printf 'stream-%s\\n' ready")
              (tm/send-keys! (:tmux/ref pane) ["Enter"])
              (assert (= marker @observed))
              (control/close! stream)
              (let [ended (control/next! stream Duration/ZERO)]
                (assert (= :terminal (:stream/status ended)))
                (assert (= :closed (:reason (control/terminal stream)))))
              (println (str "observed=" marker))
              (println "closed=true")))
          (catch clojure.lang.ExceptionInfo failure
            (if (= :tmux/unsupported-feature (:tmux/error (ex-data failure)))
              (do
                (assert (= :not-dispatched (:tmux/dispatch (ex-data failure))))
                (assert (empty? (tm/clients! server)))
                (println "requires tmux 3.7+"))
              (throw failure))))))))
