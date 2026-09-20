(ns libtmux.async-test
  (:require [clojure.test :refer [deftest is testing]]
            [libtmux.async :as async]
            [libtmux.core :as core])
  (:import [java.util.concurrent CompletableFuture CompletionStage CountDownLatch Future FutureTask
            ThreadPoolExecutor TimeUnit]))

(def ^:dynamic *submitted-value* nil)

(defn- await-uninterruptibly [^CountDownLatch latch]
  (loop []
    (when-not (try (.await latch) true
                   (catch InterruptedException _ false))
      (recur))))

(deftest runtime-requires-a-release-slot
  (is (thrown? IllegalArgumentException (async/runtime! {:max-running 1}))))

(deftest submission-conveys-bindings-and-stage-is-read-only
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [task (binding [*submitted-value* :captured]
                 (async/submit! runtime (fn [] *submitted-value*)))
          stage (async/completion-stage task)]
      (is (= :captured @task))
      (is (thrown? UnsupportedOperationException
                   (.complete ^java.util.concurrent.CompletableFuture stage :changed))))))

(deftest timed-deref-does-not-cancel-work
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [release (CountDownLatch. 1)
          task (async/submit! runtime #(.await release))]
      (is (= ::waiting (deref task 20 ::waiting)))
      (is (false? (realized? task)))
      (.countDown release)
      (is (nil? (deref task 500 ::timeout))))))

(deftest queued-cancellation-is-not-dispatched
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:max-running 2 :queue-limit 2})]
    (let [release (CountDownLatch. 1)
          running (async/submit! runtime #(.await release))
          invoked (atom false)
          queued (async/submit! runtime #(reset! invoked true))]
      (async/cancel! queued)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled" @queued))
      (is (false? @invoked))
      (.countDown release)
      (deref running 500 ::timeout))))

(deftest wait-tasks-do-not-block-an-ordinary-release
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime! {:max-running 2})]
    (let [release (CountDownLatch. 1)
          waiting (async/submit! runtime {:kind :wait} #(.await release))
          ordinary (async/submit! runtime #(.countDown release))]
      (is (nil? (deref ordinary 500 ::timeout)))
      (is (nil? (deref waiting 500 ::timeout))))))

(deftest deadline-interrupts-worker
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [interrupted (promise)
          block (CountDownLatch. 1)
          task (async/submit! runtime {:timeout-ms 30}
                              #(try
                                 (.await block)
                                 (catch InterruptedException _
                                   (deliver interrupted true))))]
      (is (thrown? clojure.lang.ExceptionInfo @task))
      (is (= true (deref interrupted 500 ::timeout))))))

(deftest close-rejects-new-work
  (let [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (.close runtime)
    (is (thrown? IllegalStateException (async/submit! runtime identity)))))

(deftest running-cancellation-waits-for-worker-cleanup
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [block (CountDownLatch. 1)
          started (CountDownLatch. 1)
          cleaning (CountDownLatch. 1)
          cleaned (CountDownLatch. 1)
          task (async/submit! runtime
                              #(try
                                 (.countDown started)
                                 (.await block)
                                 (catch InterruptedException _
                                   (.countDown cleaning)
                                   (.await cleaned))))]
      (is (.await started 500 TimeUnit/MILLISECONDS))
      (async/cancel! task)
      (is (.await cleaning 500 TimeUnit/MILLISECONDS))
      (is (= :cancelling (async/task-state task)))
      (is (false? (realized? task)))
      (.countDown cleaned)
      (is (thrown? clojure.lang.ExceptionInfo @task)))))

(deftest wait-operation-requires-wait-kind
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [task (async/submit! runtime
                              #(core/*execution-check* {:kind :wait}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a :wait task" @task)))))

(deftest admission-is-globally-bounded
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:max-running 2 :queue-limit 1})]
    (let [release (CountDownLatch. 1)
          tasks [(async/submit! runtime #(.await release))
                 (async/submit! runtime {:kind :wait} #(.await release))
                 (async/submit! runtime #(.await release))]
          rejected (async/submit! runtime #(.await release))]
      (is (= {:status :busy :kind :ordinary} rejected))
      (.countDown release)
      (doseq [task tasks] (deref task 500 ::timeout)))))

(deftest submission-is-rejected-inside-a-transaction
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (is (thrown? IllegalStateException
                 (dosync (async/submit! runtime identity))))))

(deftest managed-continuation-uses-owned-callback-executor
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [task (async/submit! runtime (constantly :ok))
          ^CompletionStage stage (async/then! runtime task
                                               (fn [_] (.getName (Thread/currentThread))))]
      (is (thrown? UnsupportedOperationException
                   (.complete ^CompletableFuture stage :changed)))
      (is (.startsWith ^String (.get (.toCompletableFuture stage) 500 TimeUnit/MILLISECONDS)
                       "libtmux-callback-")))))

(deftest timeout-validation-precedes-dispatch
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (doseq [timeout [0 -1 1.5 "1"]]
      (is (thrown? IllegalArgumentException
                   (async/submit! runtime {:timeout-ms timeout} identity))))))

(deftest cancelled-queued-task-releases-admission
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:max-running 2 :queue-limit 1})]
    (let [release (CountDownLatch. 1)
          running (async/submit! runtime #(.await release))
          waiting (async/submit! runtime {:kind :wait} #(.await release))
          queued (async/submit! runtime identity)]
      (is (true? (async/cancel! queued)))
      (is (instance? libtmux.async.Task (async/submit! runtime identity)))
      (.countDown release)
      (deref running 500 ::timeout)
      (deref waiting 500 ::timeout))))

(deftest wait-admission-preserves-an-ordinary-slot
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:max-running 2 :queue-limit 1})]
    (let [release (CountDownLatch. 1)
          waits [(async/submit! runtime {:kind :wait} #(.await release))
                 (async/submit! runtime {:kind :wait} #(.await release))]
          rejected-wait (async/submit! runtime {:kind :wait} identity)
          ordinary (async/submit! runtime #(.countDown release))]
      (is (= {:status :busy :kind :wait} rejected-wait))
      (is (nil? (deref ordinary 500 ::timeout)))
      (doseq [task waits] (deref task 500 ::timeout)))))

(deftest dequeued-cancellation-still-finalizes
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:max-running 2 :queue-limit 1})]
    (let [release (CountDownLatch. 1)
          running (async/submit! runtime #(.await release))
          queued (async/submit! runtime identity)
          ^ThreadPoolExecutor pool (.-ordinary runtime)
          ^Future future @(.-worker ^libtmux.async.Task queued)]
      ;; Model the executor boundary after dequeue and before Callable.call.
      (is (.remove pool ^Runnable future))
      (is (true? (async/cancel! queued)))
      (let [outcome (try (deref queued 300 ::stuck)
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (instance? clojure.lang.ExceptionInfo outcome))
        (is (re-find #"cancelled" (.getMessage ^Throwable outcome))))
      (is (instance? libtmux.async.Task (async/submit! runtime identity)))
      (.countDown release)
      (deref running 500 ::timeout))))

(deftest first-cancellation-wins
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [started (CountDownLatch. 1)
          release (CountDownLatch. 1)
          task (async/submit! runtime #(do (.countDown started) (.await release)))]
      (is (.await started 500 TimeUnit/MILLISECONDS))
      (is (true? (async/cancel! task)))
      (is (false? (#'async/request-cancel! task :timeout)))
      (.countDown release)
      (let [failure (try @task (catch clojure.lang.ExceptionInfo error error))]
        (is (= :cancelled (:tmux/error (ex-data failure))))))))

(deftest pending-continuations-are-bounded-before-parent-completes
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:queue-limit 1})]
    (let [release (CountDownLatch. 1)
          task (async/submit! runtime #(.await release))]
      (async/then! runtime task identity)
      (async/then! runtime task identity)
      (let [failure (try (async/then! runtime task identity)
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (= :busy (:tmux/error (ex-data failure))))
        (is (= :not-dispatched (:tmux/dispatch (ex-data failure)))))
      (.countDown release)
      (deref task 500 ::timeout))))

(deftest close-budget-includes-blocking-inline-stage-callback
  (let [runtime (async/runtime! {:close-timeout-ms 40})
        release-task (CountDownLatch. 1)
        release-callback (CountDownLatch. 1)
        task (async/submit! runtime #(.await release-task))
        stage (.toCompletableFuture ^CompletionStage (async/completion-stage task))]
    (.whenComplete stage
                   (reify java.util.function.BiConsumer
                     (accept [_ _ _] (.await release-callback))))
    (let [started (System/nanoTime)
          failure (try (.close ^libtmux.async.AsyncRuntime runtime)
                       (catch clojure.lang.ExceptionInfo error error))
          elapsed-ms (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) started))]
      (is (= :cleanup-pending (:tmux/error (ex-data failure))))
      (is (< elapsed-ms 500)))
    (.countDown release-task)
    (.countDown release-callback)))

(deftest late-worker-cleanup-remains-publishable-after-failed-close
  (let [runtime (async/runtime! {:close-timeout-ms 40})
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        task (async/submit! runtime
                            #(do
                               (.countDown started)
                               (await-uninterruptibly release)))]
    (is (.await started 500 TimeUnit/MILLISECONDS))
    (let [failure (try (.close ^libtmux.async.AsyncRuntime runtime)
                       (catch clojure.lang.ExceptionInfo error error))]
      (is (= :cleanup-pending (:tmux/error (ex-data failure))))
      (is (= :cancelling (async/task-state task)))
      (is (false? (realized? task))))
    (.countDown release)
    (let [outcome (try (deref task 500 ::stuck)
                       (catch clojure.lang.ExceptionInfo error error))]
      (is (instance? clojure.lang.ExceptionInfo outcome))
      (is (= :cancelled (:tmux/error (ex-data outcome)))))
    (is (nil? (.close ^libtmux.async.AsyncRuntime runtime)))))

(deftest lifecycle-effects-are-rejected-inside-transactions
  (let [runtime (async/runtime!)
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        task (async/submit! runtime #(do (.countDown started) (.await release)))]
    (is (.await started 500 TimeUnit/MILLISECONDS))
    (is (thrown? IllegalStateException (dosync (async/cancel! task))))
    (is (= :running (async/task-state task)))
    (is (thrown? IllegalStateException (dosync (async/then! runtime task identity))))
    (is (thrown? IllegalStateException (dosync (async/close-runtime! runtime))))
    (.countDown release)
    (deref task 500 ::timeout)
    (.close ^libtmux.async.AsyncRuntime runtime)))

(deftest blocked-inline-completions-retain-bounded-publication-admission
  (let [runtime (async/runtime! {:max-running 2 :queue-limit 1})
        release-callbacks (CountDownLatch. 1)
        tasks (atom [])]
    (try
      (dotimes [_ 3]
        (let [entered (CountDownLatch. 1)
              release-task (CountDownLatch. 1)
              task (async/submit! runtime #(.await release-task))
              stage (.toCompletableFuture ^CompletionStage (async/completion-stage task))]
          (.whenComplete stage
                         (reify java.util.function.BiConsumer
                           (accept [_ _ _]
                             (.countDown entered)
                             (.await release-callbacks))))
          (swap! tasks conj task)
          (.countDown release-task)
          (is (.await entered 500 TimeUnit/MILLISECONDS))))
      (is (= {:status :busy :kind :ordinary}
             (async/submit! runtime identity)))
      (finally
        (.countDown release-callbacks)
        (doseq [task @tasks] (deref task 500 ::timeout))
        (.close ^libtmux.async.AsyncRuntime runtime)))))

(deftest accepted-cancellation-wins-after-thunk-return
  (with-open [^libtmux.async.AsyncRuntime runtime (async/runtime!)]
    (let [entered-finalization (CountDownLatch. 1)
          release-finalization (CountDownLatch. 1)
          real-finish @#'async/finish!]
      (with-redefs [async/finish!
                    (fn [task throwable value]
                      (.countDown entered-finalization)
                      (await-uninterruptibly release-finalization)
                      (real-finish task throwable value))]
        (let [task (async/submit! runtime (constantly :success))]
          (is (.await entered-finalization 500 TimeUnit/MILLISECONDS))
          (is (true? (async/cancel! task)))
          (.countDown release-finalization)
          (let [outcome (try (deref task 500 ::stuck)
                             (catch clojure.lang.ExceptionInfo error error))]
            (is (= :cancelled (:tmux/error (ex-data outcome))))))))))

(deftest cancellation-retains-structured-partial-effect-failure
  (let [transport (ex-info "group interrupted"
                           {:tmux/error :tmux/transport
                            :tmux/dispatch :not-dispatched
                            :tmux/results [{:index 0 :status :unknown}]})
        cancellation (#'async/failure :cancelled :unknown)
        combined (#'async/reconcile-failure cancellation transport)]
    (is (= :cancelled (:tmux/error (ex-data combined))))
    (is (= :unknown (:tmux/dispatch (ex-data combined))))
    (is (= [{:index 0 :status :unknown}] (:tmux/results (ex-data combined))))
    (is (identical? transport (.getCause ^Throwable combined)))))

(deftest deadline-and-executor-rejection-share-one-finalizer
  (with-open [^libtmux.async.AsyncRuntime runtime
              (async/runtime! {:max-running 4 :queue-limit 1})]
    (let [release (CountDownLatch. 1)
          blockers (vec (repeatedly 4 #(async/submit! runtime
                                                     (fn [] (await-uninterruptibly release)))))
          publishing (CountDownLatch. 1)
          deadline-fired (CountDownLatch. 1)
          allow-interrupt (CountDownLatch. 1)
          result (atom nil)
          real-reset reset!
          real-interrupt @#'async/interrupt!
          real-execute @#'async/execute!]
      (with-redefs [async/execute!
                    (fn [executor task]
                      (if (instance? ThreadPoolExecutor executor)
                        (do
                          (.countDown publishing)
                          (await-uninterruptibly deadline-fired)
                          (.countDown allow-interrupt)
                          (throw (java.util.concurrent.RejectedExecutionException. "forced full pool")))
                        (real-execute executor task)))
                    async/interrupt!
                    (fn [task]
                      (.countDown deadline-fired)
                      (await-uninterruptibly allow-interrupt)
                      (real-interrupt task))]
        (let [submitter (.start (Thread/ofVirtual)
                                ^Runnable #(real-reset result
                                                      (async/submit! runtime {:timeout-ms 1}
                                                                     identity)))]
          (is (.await publishing 500 TimeUnit/MILLISECONDS))
          (is (.await deadline-fired 500 TimeUnit/MILLISECONDS))
          (.join submitter 500)
          (is (not (.isAlive submitter)))
          (is (= {:status :busy :kind :ordinary} @result))))
      (.countDown release)
      (doseq [task blockers] (deref task 500 ::stuck))
      (is (= 5 (.availablePermits ^java.util.concurrent.Semaphore
                                  (.-admission runtime)))))))

(deftest close-never-succeeds-with-an-open-completion-executor
  (let [^libtmux.async.AsyncRuntime runtime (async/runtime! {:close-timeout-ms 100})
        fake (CompletableFuture.)
        callback-entered (CountDownLatch. 1)
        release-callback (CountDownLatch. 1)
        callback-removed (CountDownLatch. 1)
        eligibility-checked (CountDownLatch. 1)
        allow-close (CountDownLatch. 1)
        close-result (atom nil)
        real-check @#'async/shutdown-completion-if-idle!]
    (swap! (.-callback-stages runtime) conj fake)
    (.whenComplete fake
                   (reify java.util.function.BiConsumer
                     (accept [_ _ _]
                       (.countDown callback-entered)
                       (await-uninterruptibly release-callback)
                       (swap! (.-callback-stages runtime) disj fake)
                       (.countDown callback-removed))))
    (with-redefs [async/shutdown-completion-if-idle!
                  (fn [owner]
                    (real-check owner)
                    (.countDown eligibility-checked)
                    (await-uninterruptibly allow-close))]
      (let [closer (.start (Thread/ofVirtual)
                           ^Runnable #(reset! close-result
                                              (try (.close ^libtmux.async.AsyncRuntime runtime)
                                                   :closed
                                                   (catch clojure.lang.ExceptionInfo error error))))]
        (is (.await callback-entered 500 TimeUnit/MILLISECONDS))
        (is (.await eligibility-checked 500 TimeUnit/MILLISECONDS))
        (.countDown release-callback)
        (is (.await callback-removed 500 TimeUnit/MILLISECONDS))
        (.countDown allow-close)
        (.join closer 500)
        (is (= :cleanup-pending (:tmux/error (ex-data @close-result))))))
    (is (nil? (.close ^libtmux.async.AsyncRuntime runtime)))))
