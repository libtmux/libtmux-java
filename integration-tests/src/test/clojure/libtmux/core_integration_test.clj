(ns libtmux.core-integration-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.core :as tmux]
            [libtmux.data :as data]
            [libtmux.internal.fixture :as fixture]))

(deftest typed-effects-preserve-captured-data-and-explicit-reference
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [session (tmux/new-session! server {:name "effects" :window-name "first"})
           window (first (data/windows session))
           pane (first (data/panes window))
           renamed (tmux/rename! (:tmux/ref session) "renamed")]
       (is (thrown? clojure.lang.ExceptionInfo (tmux/rename! (assoc session :session/name "edited") "rejected")))
       (is (= "effects" (:session/name session)))
       (is (= "renamed" (:session/name renamed)))
       (is (nil? (tmux/find-session! server "missing")))
       (is (= "renamed" (:session/name (tmux/refresh! (:tmux/ref session)))))
       (tmux/set-option! (:tmux/ref session) "@clj" "literal #{pane_id}")
       (is (= "literal #{pane_id}" (get (tmux/options! (:tmux/ref session)) "@clj")))
       (tmux/unset-option! (:tmux/ref session) "@clj")
       (is (nil? (get (tmux/options! (:tmux/ref session)) "@clj")))
       (tmux/set-environment! (:tmux/ref session) "CLJ_TEST" "literal")
       (is (= "literal" (get-in (tmux/environment! (:tmux/ref session)) [:values "CLJ_TEST"])))
       (tmux/remove-environment! (:tmux/ref session) "CLJ_TEST")
       (is (contains? (:removed (tmux/environment! (:tmux/ref session))) "CLJ_TEST"))
       (tmux/unset-environment! (:tmux/ref session) "CLJ_TEST")
       (is (not (contains? (:removed (tmux/environment! (:tmux/ref session))) "CLJ_TEST")))
       (tmux/set-hook! (:tmux/ref session) "after-new-window" ["display-message" "first"])
       (tmux/append-hook! (:tmux/ref session) "after-new-window" ["display-message" "second"])
       (is (= 2 (count (get (tmux/hooks! (:tmux/ref session)) "after-new-window"))))
       (tmux/unset-hook! (:tmux/ref session) "after-new-window")
       (tmux/set-buffer! server "clj" "literal\ntext")
       (is (= "literal\ntext" (tmux/show-buffer! server "clj")))
       (is (some #(= "clj" (:buffer/name %)) (tmux/buffers! server)))
       (if (.atLeast (.version ^io.github.libtmux.Server server)
                     (io.github.libtmux.TmuxVersion. 3 4 ""))
         (tmux/delete-buffer! server "clj")
         (let [error (try (tmux/delete-buffer! server "clj")
                          (catch clojure.lang.ExceptionInfo e e))]
           (is (= :tmux/unsupported-version (:tmux/error (ex-data error))))
           (is (= :not-dispatched (:tmux/dispatch (ex-data error))))))
       (is (= (:pane/id pane) (tmux/expand! (:tmux/ref pane) "#{pane_id}")))
       (is (vector? (tmux/capture! (:tmux/ref pane) {:from :history})))
       (let [created (tmux/new-window! (:tmux/ref session) {:name "second" :detached? true})
             split (tmux/split-pane! (:tmux/ref (first (data/panes created)))
                                    {:direction :right :percent 30})]
         (is (= "second" (:window/name created)))
         (is (string? (:pane/id split)))
         (tmux/select! (:tmux/ref split))
         (is (:pane/active? (tmux/refresh! (:tmux/ref split))))
         (tmux/resize! (:tmux/ref split) 20 10)
         (tmux/kill! (:tmux/ref split))
         (is (nil? (tmux/refresh! (:tmux/ref split)))))
       (tmux/kill! (:tmux/ref renamed))
       (is (nil? (tmux/find-session! server "renamed")))))))

(deftest contextual-refresh-does-not-escape-unlinked-session
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [first-session (tmux/new-session! server {:name "link-first"})
           second-session (tmux/new-session! server {:name "link-second"})
           window (first (data/windows first-session))]
       (tmux/link-window! (:tmux/ref window) (:tmux/ref second-session))
       (let [linked (first (filter #(= (:window/id window) (:window/id %))
                                   (data/windows (tmux/refresh! (:tmux/ref second-session)))))
             pane (first (data/panes linked))]
         (is (data/same-entity? (first (data/panes window)) pane))
         (is (not (data/same-link? (first (data/panes window)) pane)))
         (tmux/unlink-window! (:tmux/ref linked))
         (is (nil? (tmux/refresh! (:tmux/ref linked))))
         (is (nil? (tmux/refresh! (:tmux/ref pane))))
         (is (some? (tmux/refresh! (:tmux/ref window)))))))))

(deftest closing-resources-does-not-kill-borrowed-server
  (fixture/with-owned-server
   (fn [{:keys [server socket binary]}]
     (let [owned (tmux/open! {:socket-path socket :binary binary})
           captured (tmux/snapshot! owned)]
       (tmux/close! owned)
       (is (seq (:tmux/sessions captured)))
       (is (seq (tmux/sessions! server)))
       (let [error (try (tmux/snapshot! owned) (catch clojure.lang.ExceptionInfo e e))]
         (is (= :tmux/closed (:tmux/error (ex-data error)))))))))

(deftest mutation-followed-by-capture-failure-is-not-safe-to-retry
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [cause (ex-info "capture failed" {:tmux/error :tmux/transport :tmux/dispatch :not-dispatched})
           error (with-redefs [data/from-capture (fn [_] (throw cause))]
                   (try (tmux/new-session! server {:name "mutation-applied"})
                        (catch clojure.lang.ExceptionInfo e e)))]
       (is (identical? cause (.getCause ^Throwable error)))
       (is (= :unknown (:tmux/dispatch (ex-data error))))
       (is (= :possible (:tmux/effects (ex-data error))))
       (is (some? (tmux/find-session! server "mutation-applied")))))))

(deftest refresh-fences-reindex-and-replacement-slots
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [session (tmux/new-session! server {:name "reindex"})
           original (first (data/windows session))
           original-pane (first (data/panes original))]
       (tmux/move-window! (:tmux/ref original) (:tmux/ref session) 7)
       (is (nil? (tmux/refresh! (:tmux/ref original))))
       (is (nil? (tmux/refresh! (:tmux/ref original-pane))))
       (let [moved (first (data/windows (tmux/refresh! (:tmux/ref session))))
             replacement (tmux/new-window! (:tmux/ref session)
                                          {:name "replacement" :index (:window/index original)})
             error (try (tmux/select! (:tmux/ref original))
                        (catch clojure.lang.ExceptionInfo e e))]
         (is (data/same-entity? original moved))
         (is (not (data/same-link? original moved)))
         (is (not (data/same-entity? original replacement)))
         (is (nil? (tmux/refresh! (:tmux/ref original))))
         (is (= :tmux/not-found (:tmux/error (ex-data error))))
         (is (:window/active? (tmux/refresh! (:tmux/ref replacement)))))))))

(deftest split-retains-the-callers-linked-window-context
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [session (tmux/new-session! server {:name "split-links"})
           window (first (data/windows session))
           target (str (:session/id session) ":9")]
       (is (zero? (:exit-code (tmux/raw! server ["link-window" "-s" (:window/id window) "-t" target]))))
       (let [linked (first (filter #(= 9 (:window/index %)) (tmux/windows! server)))
             pane (first (data/panes linked))
             created (tmux/split-pane! (:tmux/ref pane) {:direction :right})]
         (is (= (:tmux/link pane) (:tmux/link created)))
         (is (not= (:pane/id pane) (:pane/id created)))
         (tmux/unlink-window! (:tmux/ref linked))
         (is (nil? (tmux/refresh! (:tmux/ref pane))))
         (is (nil? (tmux/refresh! (:tmux/ref created))))
         (is (zero? (:exit-code (tmux/raw! server ["link-window" "-s" (:window/id window) "-t" target]))))
         (let [restored (tmux/refresh! (:tmux/ref created))]
           (is (data/same-link? created restored))))))))

(deftest two-handle-effects-reject-cross-endpoint-references
  (fixture/with-owned-server
   (fn [{left :server}]
     (fixture/with-owned-server
      (fn [{right :server}]
        (let [left-session (first (tmux/sessions! left))
              left-window (first (data/windows left-session))
              left-pane (first (data/panes left-window))
              right-session (first (tmux/sessions! right))
              right-window (first (data/windows right-session))]
          (doseq [operation [#(tmux/link-window! (:tmux/ref left-window) (:tmux/ref right-session))
                             #(tmux/move-window! (:tmux/ref left-window) (:tmux/ref right-session) 9)
                             #(tmux/join-pane! (:tmux/ref left-pane) (:tmux/ref right-window))]]
            (let [error (try (operation) (catch clojure.lang.ExceptionInfo e e))]
              (is (= :tmux/validation (:tmux/error (ex-data error))))
              (is (= :not-dispatched (:tmux/dispatch (ex-data error))))
              (is (instance? IllegalArgumentException (.getCause ^Throwable error)))))
          (is (= 1 (count (tmux/windows! left))))
          (is (= 1 (count (tmux/windows! right))))))))))

(deftest singleton-command-is-a-literal-executable-with-no-arguments
  (fixture/with-owned-server
   (fn [{:keys [server socket binary]}]
     (let [script (.resolve (.getParent ^java.nio.file.Path socket) "literal executable;name")]
       (java.nio.file.Files/writeString script "#!/bin/sh\n[ \"$#\" -eq 0 ] || exit 1\n\"$CLJ_TMUX\" -S \"$CLJ_SOCKET\" wait-for -S singleton-started\nexec /bin/cat\n"
                                       (make-array java.nio.file.OpenOption 0))
       (java.nio.file.Files/setPosixFilePermissions
        script (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))
       (try
         (let [session (tmux/new-session! server {:name "literal-executable" :command [(str script)]
                                                   :environment {"CLJ_TMUX" binary "CLJ_SOCKET" (str socket)}})]
           (is (= :signalled (tmux/await-channel! server "singleton-started"
                                                (java.time.Duration/ofMillis 500))))
           (is (= "literal-executable" (:session/name session)))
           (is (= 1 (count (data/panes session)))))
         (finally (java.nio.file.Files/deleteIfExists script)))))))

(deftest command-groups-attribute-reads-partial-failure-and-dependent-layout
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [session (first (tmux/sessions! server))
           pane (first (data/panes session))
           reads (mapv (fn [index] ["display-message" "-p" "-t" (:pane/id pane)
                                    (str index ":#{pane_id}")]) (range 4))]
       (doseq [run [tmux/batch! tmux/chain!]]
         (let [results (run server reads)]
           (is (= [:complete :complete :complete :complete] (mapv :operation/outcome results)))
           (is (= (mapv #(vector (str % ":" (:pane/id pane))) (range 4))
                  (mapv :operation/stdout results))))
         (let [results (run server [["set-buffer" "-b" "group-before" "applied"]
                                    ["select-pane" "-t" "%999999999"]
                                    ["set-buffer" "-b" "group-after" "skipped"]])]
           (is (= [:complete :failed :skipped] (mapv :operation/outcome results)))
           (is (= "applied" (tmux/show-buffer! server "group-before")))
           (is (not-any? #(= "group-after" (:buffer/name %)) (tmux/buffers! server)))))
       (let [results (tmux/chain! server [["new-window" "-t" (:session/id session) "-n" "group-layout"]
                                          ["split-window" "-h"]
                                          ["select-layout" "even-horizontal"]
                                          ["display-message" "-p" "#{window_name}:#{window_panes}"]])]
         (is (= [:complete :complete :complete :complete] (mapv :operation/outcome results)))
         (is (= ["group-layout:2"] (:operation/stdout (last results)))))))))

(deftest waiting-groups-reserve-release-capacity
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (with-open [transport (io.github.libtmux.transport.ProcessTransport. 2)
                 shared (io.github.libtmux.Server/using (.config ^io.github.libtmux.Server server) transport)]
       (doseq [run [tmux/batch! tmux/chain!]]
         (let [result (promise)
               worker (Thread/startVirtualThread
                       ^Runnable (fn []
                                   (deliver result
                                            (try (run shared [["wait-for" "-S" "group-started"]
                                                               ["wait-for" "group-release"]]
                                                      {:kind :wait})
                                                 (catch Exception e e)))))]
           (try
             (is (= :signalled (tmux/await-channel! server "group-started" (java.time.Duration/ofMillis 800))))
             (let [refused (try (run shared [["wait-for" "second-wait"]] {:kind :wait})
                                (catch clojure.lang.ExceptionInfo e e))]
               (is (= :not-dispatched (:tmux/dispatch (ex-data refused))))
               (is (= [:skipped] (mapv :operation/outcome (:tmux/results (ex-data refused))))))
             (tmux/signal-channel! shared "group-release")
             (let [finished (deref result 800 ::timeout)]
               (is (vector? finished))
               (is (= [:complete :complete] (mapv :operation/outcome finished))))
             (finally
               (.interrupt worker)
               (.join worker 800)
               (is (not (.isAlive worker)))))))))))

(deftest cancelled-groups-report-unknown-positions-and-preserve-partial-effects
  (fixture/with-owned-server
   (fn [{:keys [server]}]
     (let [result (promise)
           worker (Thread/startVirtualThread
                   ^Runnable (fn []
                               (deliver result
                                        (try (tmux/batch! server
                                                          [["set-buffer" "-b" "cancel-before" "applied"]
                                                           ["wait-for" "-S" "cancel-started"]
                                                           ["wait-for" "cancel-blocked"]
                                                           ["set-buffer" "-b" "cancel-after" "uncertain"]]
                                                          {:kind :wait})
                                             (catch Exception e e)))))]
       (try
         (is (= :signalled (tmux/await-channel! server "cancel-started" (java.time.Duration/ofMillis 800))))
         (.interrupt worker)
         (let [error (deref result 800 ::timeout)]
           (is (instance? clojure.lang.ExceptionInfo error))
           (is (= :unknown (:tmux/dispatch (ex-data error))))
           (is (= [:unknown :unknown :unknown :unknown]
                  (mapv :operation/outcome (:tmux/results (ex-data error)))))
           (is (instance? io.github.libtmux.transport.TmuxTransportException
                          (.getCause ^Throwable error))))
         (is (= "applied" (tmux/show-buffer! server "cancel-before")))
         (finally
           (tmux/signal-channel! server "cancel-blocked")
           (.interrupt worker)
           (.join worker 800)
           (is (not (.isAlive worker)))))))))
