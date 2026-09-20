(ns libtmux.internal.fixture-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.async :as async]
            [libtmux.internal.fixture :as fixture])
  (:import [java.lang ProcessHandle Thread]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(defn- exists? [^Path socket]
  (Files/exists socket
                (make-array java.nio.file.LinkOption 0)))

(defn- capture-fixture-process [binary socket]
  (let [capture (ns-resolve 'libtmux.internal.fixture 'capture-process)]
    (capture binary socket)))

(defn- await-exit! [^ProcessHandle process]
  (.get (.onExit process) 900 TimeUnit/MILLISECONDS))

(defn- replace-fixture-daemon! [^Path socket]
  (let [binary (System/getProperty "libtmux.tmux" "tmux")
        original (capture-fixture-process binary socket)
        directory (.getParent socket)
        config (.resolve directory "foreign.conf")]
    (when-not original
      (throw (ex-info "could not capture the fixture tmux process" {:socket socket})))
    (.destroy ^ProcessHandle (:process original))
    (await-exit! (:process original))
    (Files/deleteIfExists socket)
    (Files/writeString config "" (make-array java.nio.file.OpenOption 0))
    (let [^java.util.List arguments [binary "-f" (str config) "-S" (str socket)
                                     "new-session" "-d" "-s" "foreign-fixture"]
          ^Process process (.start (ProcessBuilder. arguments))
          completed? (.waitFor process 900 TimeUnit/MILLISECONDS)
          replacement (capture-fixture-process binary socket)]
      (when-not (and completed? (zero? (.exitValue process)) replacement)
        (throw (ex-info "could not start a replacement tmux process" {:socket socket})))
      (assoc replacement :socket socket :config config))))

(defn- interrupted-fixture-cleans! [options expected-error]
  (let [started (promise)
        release (java.util.concurrent.CountDownLatch. 1)
        ^libtmux.async.AsyncRuntime runtime (async/runtime! {:close-timeout-ms 900})
        task (async/submit! runtime options
                            #(fixture/with-owned-server
                               (fn [{:keys [socket binary]}]
                                 (let [owner (capture-fixture-process binary socket)]
                                   (deliver started {:socket socket
                                                     :directory (.getParent ^Path socket)
                                                     :process (:process owner)
                                                     :worker (Thread/currentThread)})
                                   (.await release)))))]
    (let [owned
          (try
            (let [owned (deref started 800 ::not-started)]
              (is (not= ::not-started owned))
              (when (map? owned)
                (is (instance? ProcessHandle (:process owned)))
                (is (instance? Thread (:worker owned)))
                (is (exists? (:socket owned))))
              (when (= expected-error :cancelled)
                (is (true? (async/cancel! task))))
              (let [failure (try @task (catch Throwable failure failure))]
                (is (= expected-error (:tmux/error (ex-data failure)))))
              owned)
            (finally
              (.countDown release)
              (try
                (deref task 900 ::still-running)
                (catch Throwable _))
              (async/close-runtime! runtime)))]
      (when (map? owned)
        (is (false? (.isAlive ^ProcessHandle (:process owned))))
        ;; Task completion is published before its virtual worker returns to
        ;; the executor. Wait for that worker's terminal transition before
        ;; asserting fixture ownership cleanup.
        (.join ^Thread (:worker owned) 900)
        (is (false? (.isAlive ^Thread (:worker owned))))
        (is (not (exists? (:socket owned))))
        (is (not (exists? (:directory owned))))))))

(deftest owned-server-is-removed-after-success
  (let [seen (atom nil)]
    (fixture/with-owned-server
      (fn [{:keys [socket] :as owned}]
        (reset! seen socket)
        (is (instance? io.github.libtmux.Server (:server owned)))
        (is (exists? socket))))
    (is (not (exists? @seen)))))

(deftest owned-server-is-removed-after-exception
  (let [seen (atom nil)]
    (is (thrown? Exception
                 (fixture/with-owned-server
                   (fn [{:keys [socket]}]
                     (reset! seen socket)
                     (throw (Exception. "fixture failure"))))))
    (is (not (exists? @seen)))))

(deftest server-is-removed-when-setup-fails-after-start
  (let [seen (atom nil)]
    (is (thrown? Exception
                 (binding [fixture/*after-start*
                           (fn [socket]
                             (reset! seen socket)
                             (throw (Exception. "setup failure")))]
                   (fixture/with-owned-server (fn [_])))))
    (is (not (exists? @seen)))))

(deftest selected-version-must-match
  (let [property "libtmux.tmux.expected"
        previous (System/getProperty property)]
    (try
      (System/setProperty property "not-a-tmux-version")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not match"
                            (fixture/with-owned-server (fn [_]))))
      (finally
        (if previous
          (System/setProperty property previous)
          (System/clearProperty property))))))

(deftest changed-process-identity-is-not-owned
  (let [process (ProcessHandle/current)
        owned {:process process
               :pid (.pid process)
               :command "tmux"
               :arguments ["-S" "/tmp/libtmux-java-test/owned"]
               :started nil}
        same-process? (ns-resolve 'libtmux.internal.fixture 'same-process?)
        identity (ns-resolve 'libtmux.internal.fixture 'process-identity)]
    (with-redefs-fn {identity (fn [_] owned)}
      #(is (true? (same-process? owned))))
    (with-redefs-fn {identity (fn [_] (assoc owned :pid (inc (:pid owned))))}
      #(is (false? (same-process? owned))))))

(deftest replacement-daemon-survives-fixture-refusal
  (let [replacement (atom nil)
        failure (try
                  (binding [fixture/*after-start*
                            (fn [socket]
                              (reset! replacement (replace-fixture-daemon! socket))
                              (throw (ex-info "fixture process was replaced" {:socket socket})))]
                    (fixture/with-owned-server (fn [_])))
                  nil
                  (catch Throwable failure failure))]
    (try
      (is (instance? clojure.lang.ExceptionInfo failure))
      (is (some #(re-find #"replacement" (.getMessage ^Throwable %))
                (.getSuppressed ^Throwable failure)))
      (when-let [owned @replacement]
        (is (.isAlive ^ProcessHandle (:process owned)))
        (is (exists? (:socket owned))))
      (finally
        (when-let [owned @replacement]
          (.destroy ^ProcessHandle (:process owned))
          (await-exit! (:process owned))
          (Files/deleteIfExists (:socket owned))
          (Files/deleteIfExists (:config owned))
          (Files/deleteIfExists (.resolve (.getParent ^Path (:socket owned)) "tmux.conf"))
          (Files/deleteIfExists (.getParent ^Path (:socket owned))))))))

(deftest cancellation-reclaims-the-owned-fixture-resources
  (interrupted-fixture-cleans! {} :cancelled))

(deftest deadline-reclaims-the-owned-fixture-resources
  (interrupted-fixture-cleans! {:timeout-ms 800} :timeout))
