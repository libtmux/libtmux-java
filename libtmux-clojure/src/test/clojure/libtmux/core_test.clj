(ns libtmux.core-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.core :as tmux])
  (:import [io.github.libtmux Server ServerConfig]
           [io.github.libtmux.transport TmuxTransport CommandRequest CommandResult DispatchOutcome]
           [io.github.libtmux.exception DispatchException DispatchException$Failed]))

(defn- version-probe?
  "The hook-free admission probe: run-shell -C '<marker>#{version}'."
  [^CommandRequest request]
  (let [commands (.commands request)]
    (and (= 1 (count commands))
         (let [argv (vec (first commands))]
           (and (= 3 (count argv)) (= ["run-shell" "-C"] (subvec argv 0 2))
                (.startsWith ^String (argv 2) "libtmux-version-"))))))

(defn- probe-reply [^CommandRequest request]
  (let [^String format (nth (first (.commands request)) 2)]
    (CommandResult. 1 [] [(str "unknown command: "
                               (subs format 0 (- (count format) (count "#{version}")))
                               "3.7c")])))

(defn- ^Server fake-server
  "A server over a fake transport. Admission is answered, so `dispatch` sees only the
  requests a call itself makes."
  [dispatch]
  (Server/using (.build (ServerConfig/builder))
                (reify TmuxTransport
                  (execute [_ request]
                    (if (version-probe? request) (probe-reply request) (dispatch request)))
                  (close [_]))))

(defn- failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest validation-rejects-before-dispatch
  (let [calls (atom 0)]
    (with-open [server (fake-server (fn [_] (swap! calls inc) (CommandResult. 0 [] [])))]
      (doseq [options [{:unknown true} {:command "echo nope"} {:name 1} {:environment {:a "b"}}]]
        (let [error (failure #(tmux/new-session! server options))]
          (is (= :tmux/validation (:tmux/error (ex-data error))))
          (is (= :not-dispatched (:tmux/dispatch (ex-data error))))))
      (is (= :tmux/validation
             (:tmux/error (ex-data (failure #(tmux/snapshot! {:tmux/ref server}))))))
      (is (zero? @calls)))
    (is (= :tmux/validation (:tmux/error (ex-data (failure #(tmux/open! {}))))))
    (is (= :tmux/validation (:tmux/error (ex-data (failure #(tmux/kill! {:pane/id "%0"}))))))))

(deftest stm-and-execution-hook-precede-dispatch
  (let [calls (atom 0)]
    (with-open [server (fake-server (fn [_] (swap! calls inc) (CommandResult. 0 [] [])))]
      (is (thrown? IllegalStateException (dosync (tmux/raw! server ["list-sessions"]))))
      (binding [tmux/*execution-check* (fn [metadata]
                                       (is (= {:operation :raw :kind :mutation} metadata))
                                       (throw (ex-info "expired" {:expired true})))]
        (is (:expired (ex-data (failure #(tmux/raw! server ["list-sessions"]))))))
      (is (zero? @calls)))))

(deftest original-cause-and-conservative-dispatch-survive
  (let [cause (DispatchException$Failed. "sensitive payload" DispatchOutcome/NOT_DISPATCHED nil)]
    (with-open [server (fake-server (fn [_] (throw cause)))]
      (let [error (failure #(tmux/raw! server ["set-buffer" "private"]))]
        (is (identical? cause (.getCause ^Throwable error)))
        (is (= {:tmux/error :tmux/transport :tmux/operation :raw
                :tmux/phase :unknown :tmux/dispatch :not-dispatched :tmux/effects :none}
               (tmux/error-summary error)))
        (is (not (.contains (pr-str (tmux/error-summary error)) "sensitive")))))))

(deftest programmer-errors-and-interruption-are-not-reclassified
  (doseq [cause [(IllegalArgumentException. "caller bug")
                 (InterruptedException. "cancelled")]]
    (with-open [server (fake-server (fn [_] (throw cause)))]
      (let [caught (try (tmux/raw! server ["list-sessions"])
                        (catch Exception e e))]
        (is (identical? cause caught))))))


(deftest typed-single-effect-preserves-transport-certainty
  (doseq [[outcome dispatch effects] [[DispatchOutcome/NOT_DISPATCHED :not-dispatched :none]
                                      [DispatchOutcome/UNKNOWN :unknown :possible]
                                      [DispatchOutcome/COMPLETE :complete :possible]]]
    (let [cause (DispatchException$Failed. "transport detail" outcome nil)]
      (with-open [server (fake-server (fn [_] (throw cause)))]
        (let [error (failure #(tmux/set-buffer! server "buffer" "contents"))]
          (is (identical? cause (.getCause ^Throwable error)))
          (is (= dispatch (:tmux/dispatch (ex-data error))))
          (is (= effects (:tmux/effects (ex-data error)))))))))

(defn- run-group [kind server operations]
  ((ns-resolve 'libtmux.core kind) server operations))

(defn- marked-reply
  "Answers a group the way tmux does. Admission puts a version fence ahead of the caller's
  commands, which prints nothing; each caller command is then followed by its marker."
  [request exit]
  (let [commands (rest (.commands ^io.github.libtmux.transport.CommandRequest request))]
    (CommandResult. exit
                    (vec (mapcat (fn [[index marker]] [(str "value-" index) (last marker)])
                                 (map-indexed vector (take-nth 2 (rest commands)))))
                    (if (zero? exit) [] ["late failure"]))))

(deftest groups-use-fresh-builders-and-positioned-output
  (doseq [kind ['batch! 'chain!]]
    (let [sizes (atom [])]
      (with-open [server (fake-server (fn [request]
                                       (swap! sizes conj (count (.commands ^io.github.libtmux.transport.CommandRequest request)))
                                       (marked-reply request 0)))]
        (dotimes [_ 2]
          (let [results (run-group kind server [["display-message" "-p" "one"]
                                                ["display-message" "-p" "two"]])]
            (is (= [0 1] (mapv :operation/index results)))
            (is (= [:complete :complete] (mapv :operation/outcome results)))
            (is (= [["value-0"] ["value-1"]] (mapv :operation/stdout results)))))
        (is (= [5 5] @sizes) "one fence, then each command and its marker")))))

(deftest groups-preserve-unknown-and-reject-invalid-input
  (doseq [kind ['batch! 'chain!]]
    (let [calls (atom 0)]
      (with-open [server (fake-server (fn [request] (swap! calls inc) (marked-reply request 1)))]
        (let [results (run-group kind server [["display-message" "-p" "one"]
                                              ["display-message" "-p" "two"]])]
          (is (= [:complete :unknown] (mapv :operation/outcome results))))
        (doseq [operations ['(["list-sessions"]) [["list-sessions"] []] [[1]]]]
          (is (= :tmux/validation (:tmux/error (ex-data (failure #(run-group kind server operations)))))))
        (is (= 1 @calls))
        (is (thrown? IllegalStateException (dosync (run-group kind server [["list-sessions"]]))))
        (is (= 1 @calls))))))

(deftest group-transport-errors-preserve-all-unresolved-positions
  (doseq [kind ['batch! 'chain!]
          [outcome dispatch result-outcome] [[DispatchOutcome/NOT_DISPATCHED :not-dispatched :skipped]
                                              [DispatchOutcome/UNKNOWN :unknown :unknown]]]
    (let [cause (DispatchException$Failed. "transport failed" outcome nil)]
      (with-open [server (fake-server (fn [_] (throw cause)))]
        (let [error (failure #(run-group kind server [["set-buffer" "a"] ["set-buffer" "b"]]))]
          (is (identical? cause (.getCause ^Throwable error)))
          (is (= dispatch (:tmux/dispatch (ex-data error))))
          (is (= [result-outcome result-outcome]
                 (mapv :operation/outcome (:tmux/results (ex-data error))))))))))

(deftest blocking-groups-require-explicit-wait-classification
  (let [calls (atom 0)]
    (with-open [server (fake-server (fn [request] (swap! calls inc) (marked-reply request 0)))]
      (doseq [kind ['batch! 'chain!]]
        (is (= :tmux/validation
               (:tmux/error (ex-data (failure #(run-group kind server [["wait-for" "blocked"]]))))))
        (is (= :tmux/validation
               (:tmux/error (ex-data (failure #(run-group kind server [["wait-for" "-L" "lock"]])))))))
      (binding [tmux/*execution-check* (fn [metadata]
                                        (is (= :wait (:kind metadata)))
                                        (throw (ex-info "classified wait" {:classified true})))]
        (doseq [kind ['batch! 'chain!]]
          (is (:classified
               (ex-data (failure #((ns-resolve 'libtmux.core kind)
                                    server [["wait-for" "blocked"]] {:kind :wait})))))))
      (is (zero? @calls)))))
