(ns libtmux.async
  (:require [libtmux.core :as core])
  (:import [java.lang AutoCloseable]
           [java.util.concurrent ArrayBlockingQueue Callable CompletableFuture CountDownLatch Executor ExecutorService
            Executors Future FutureTask RejectedExecutionException ScheduledExecutorService ScheduledFuture Semaphore ThreadFactory
            ScheduledThreadPoolExecutor ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.function BiConsumer Function]))

(declare cancel! close-runtime!)

(deftype Task [state started worker deadline stage kind runtime cancellation permits publication-permit]
  clojure.lang.IDeref
  (deref [_]
    (try (.get ^CompletableFuture stage)
         (catch java.util.concurrent.ExecutionException failure (throw (.getCause failure)))))
  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-value]
    (try (.get ^CompletableFuture stage timeout-ms TimeUnit/MILLISECONDS)
         (catch java.util.concurrent.TimeoutException _ timeout-value)
         (catch java.util.concurrent.ExecutionException failure (throw (.getCause failure)))))
  clojure.lang.IPending
  (isRealized [_] (.isDone ^CompletableFuture stage)))

(deftype AsyncRuntime [^ExecutorService ordinary ^ExecutorService waits
                       ^ExecutorService callbacks ^ExecutorService completions
                       ^ScheduledExecutorService deadlines
                       ^Semaphore admission ^Semaphore wait-admission ^Semaphore callback-admission
                       ^Semaphore publication-admission
                       tasks callback-stages lifecycle-lock
                       closed close-timeout-ms]
  AutoCloseable
  (close [this] (close-runtime! this)))

(defn- virtual-factory [prefix]
  (.factory (.name (Thread/ofVirtual) (str prefix "-") 0)))

(defn- executor [workers queue-limit prefix]
  (ThreadPoolExecutor. workers workers 0 TimeUnit/MILLISECONDS
                       (ArrayBlockingQueue. queue-limit)
                       ^ThreadFactory (virtual-factory prefix)
                       (ThreadPoolExecutor$AbortPolicy.)))

(defn- completion-executor []
  (Executors/newThreadPerTaskExecutor (virtual-factory "libtmux-complete")))

(defn- deadline-executor []
  (doto (ScheduledThreadPoolExecutor. 1 ^ThreadFactory (virtual-factory "libtmux-deadline"))
    (.setRemoveOnCancelPolicy true)))

(defn- execute! [^ExecutorService executor ^Runnable task]
  (.execute executor task))

(defn runtime!
  "Creates an owned bounded runtime. Close it to reclaim every owned executor."
  ([] (runtime! {}))
  ([options]
   (when-not (map? options)
     (throw (IllegalArgumentException. "runtime options must be a map")))
   (let [allowed #{:max-running :queue-limit :close-timeout-ms}
         unknown (seq (remove allowed (keys options)))
         {:keys [max-running queue-limit close-timeout-ms]
          :or {max-running 4 queue-limit 64 close-timeout-ms 900}} options]
     (io!
      (when unknown (throw (IllegalArgumentException. (str "unknown runtime options: " unknown))))
      (when-not (and (integer? max-running) (<= 2 max-running Integer/MAX_VALUE))
        (throw (IllegalArgumentException. "max-running must be at least 2")))
      (when-not (and (integer? queue-limit) (<= 1 queue-limit Integer/MAX_VALUE)
                     (integer? close-timeout-ms) (<= 1 close-timeout-ms Long/MAX_VALUE))
        (throw (IllegalArgumentException. "queue-limit and close-timeout-ms must be positive")))
      (AsyncRuntime. (executor (dec max-running) queue-limit "libtmux-work")
                     (executor 1 queue-limit "libtmux-wait")
                     (executor 1 queue-limit "libtmux-callback")
                     (completion-executor)
                     (deadline-executor)
                     (Semaphore. (+ max-running queue-limit))
                     (Semaphore. (dec (+ max-running queue-limit)))
                     (Semaphore. (inc queue-limit))
                     (Semaphore. (+ max-running queue-limit))
                     (atom #{}) (atom #{}) (Object.) (atom false) close-timeout-ms)))))

(defn task-kind [^Task task] (.-kind task))
(defn task-state [^Task task] @(.-state task))
(defn completion-stage [^Task task] (.minimalCompletionStage ^CompletableFuture (.-stage task)))

(defn- failure [reason certainty]
  (ex-info (if (= reason :timeout) "task deadline expired" "task cancelled")
           {:tmux/error reason :tmux/operation :async-task :tmux/phase :execution
            :tmux/dispatch certainty}))

(defn- reconcile-failure [cancellation thrown]
  (cond
    (nil? cancellation) thrown
    (or (nil? thrown) (identical? cancellation thrown)) cancellation
    :else
    ;; Cancellation remains the terminal classification and certainty, while
    ;; structured partial-effect evidence and the original cause stay reachable.
    (ex-info (.getMessage ^Throwable cancellation)
             (merge (ex-data thrown) (ex-data cancellation))
             thrown)))

(defn- finish! [^Task task throwable value]
  (locking task
    (let [state (.-state task)
          ^AsyncRuntime runtime (.-runtime task)
          throwable (reconcile-failure @(.-cancellation task) throwable)]
      (when-not (= :terminal @state)
        (reset! state :cleanup-pending)
        (when-let [^ScheduledFuture timer @(.-deadline task)] (.cancel timer false))
        (let [ready (CountDownLatch. 1)]
         (locking (.-lifecycle-lock runtime)
          ;; The publisher cannot be shut down while this task remains in the
          ;; registry. Acceptance therefore precedes the final registry drop.
          (.execute ^ExecutorService (.-completions runtime)
                    ^Runnable #(do (.await ready)
                                   (try
                                     (if throwable
                                       (.completeExceptionally ^CompletableFuture (.-stage task) throwable)
                                       (.complete ^CompletableFuture (.-stage task) value))
                                     (finally
                                       (.release ^Semaphore (.-publication-permit task))))))
          (swap! (.-tasks runtime) disj task)
          (doseq [^Semaphore permit (.-permits task)] (.release permit))
          (reset! state :terminal)
          (.countDown ready)))))))

(defn- interrupt! [^Task task]
  (when-let [^Future future @(.-worker task)]
    (let [^ThreadPoolExecutor pool (if (= :wait (.-kind task))
                                      (.-waits ^AsyncRuntime (.-runtime task))
                                      (.-ordinary ^AsyncRuntime (.-runtime task)))]
      (.cancel future true)
      (.remove pool ^Runnable future))))

(defn- request-cancel! [^Task task reason]
  (let [accepted (locking task
                   (let [state (.-state task)]
                     (when (#{:queued :running} @state)
                       (let [problem (failure reason (if (= :queued @state)
                                                       :not-dispatched :unknown))]
                         (reset! (.-cancellation task) problem)
                         (reset! state :cancelling)
                         problem))))]
    (if accepted
      (do (interrupt! task) true)
      false)))

(defn- reject! [^Task task]
  (let [problem (failure :busy :not-dispatched)
        cancellable? (locking task
                       (when (= :queued @(.-state task))
                         (reset! (.-cancellation task) problem)
                         (reset! (.-state task) :cancelling))
                       (= :cancelling @(.-state task)))]
    (when cancellable?
      ;; This FutureTask was never accepted by an executor. Cancellation calls
      ;; done synchronously, which is the task's single finalization owner.
      (.cancel ^Future @(.-worker task) false))))

(defn cancel!
  "Requests cancellation. The first accepted cancellation reason wins."
  [task]
  (when-not (instance? Task task)
    (throw (IllegalArgumentException. "cancel! requires a task")))
  (io! (request-cancel! task :cancelled)))

(defn submit!
  "Submits a zero-argument function, returning a Task or a :busy result."
  ([runtime thunk] (submit! runtime {} thunk))
  ([^AsyncRuntime runtime options thunk]
   (when-not (and (instance? AsyncRuntime runtime) (map? options) (ifn? thunk))
     (throw (IllegalArgumentException. "submit! requires a runtime, option map, and function")))
   (let [allowed #{:kind :timeout-ms}
         unknown (seq (remove allowed (keys options)))
         {:keys [kind timeout-ms] :or {kind :ordinary}} options]
     (io!
      (when unknown (throw (IllegalArgumentException. (str "unknown submit options: " unknown))))
      (when @(.-closed runtime) (throw (IllegalStateException. "runtime is closed")))
      (when-not (#{:ordinary :wait} kind)
        (throw (IllegalArgumentException. "task kind must be :ordinary or :wait")))
      (when-not (or (nil? timeout-ms)
                    (and (integer? timeout-ms) (<= 1 timeout-ms Long/MAX_VALUE)))
        (throw (IllegalArgumentException. "timeout-ms must be a positive integer")))
      (let [global (.-admission runtime)
            wait-permit (when (= :wait kind) (.-wait-admission runtime))
            publication-permit (.-publication-admission runtime)
            acquired-global? (.tryAcquire ^Semaphore global)
            acquired-wait? (or (nil? wait-permit) (.tryAcquire ^Semaphore wait-permit))
            acquired-publication? (and acquired-global? acquired-wait?
                                       (.tryAcquire ^Semaphore publication-permit))]
      (if-not (and acquired-global? acquired-wait? acquired-publication?)
        (do (when acquired-global? (.release ^Semaphore global))
            (when (and wait-permit acquired-wait?) (.release ^Semaphore wait-permit))
            (when acquired-publication? (.release ^Semaphore publication-permit))
        {:status :busy :kind kind}
        )
        (let [state (atom :queued) started (atom false) worker (atom nil) deadline (atom nil) stage (CompletableFuture.)
              cancellation (atom nil)
              task (Task. state started worker deadline stage kind runtime cancellation
                          (cond-> [global] wait-permit (conj wait-permit)) publication-permit)
              bindings (get-thread-bindings)
              pool (if (= :wait kind) (.-waits runtime) (.-ordinary runtime))
              check (fn [{operation-kind :kind}]
                      (when-let [problem @cancellation] (throw problem))
                      (when (and (= operation-kind :wait) (not= kind :wait))
                        (throw (ex-info "wait operation requires a :wait task"
                                        {:tmux/error :invalid-task-kind
                                         :tmux/operation :async-task
                                         :tmux/phase :validation
                                         :tmux/dispatch :not-dispatched}))))
              call (reify Callable
                     (call [_]
                       (let [run? (locking task
                                    (when (= :queued @state)
                                      (reset! started true)
                                      (reset! state :running)
                                      true))]
                       (if run?
                         (try
                           (let [value (with-bindings (assoc bindings
                                                            #'core/*execution-check* check)
                                         (thunk))]
                             (finish! task @cancellation value))
                           (catch Throwable thrown
                             (finish! task thrown nil)))
                         (finish! task @cancellation nil)))
                       nil))
              future (proxy [FutureTask] [call]
                       (done []
                         ;; FutureTask invokes done even when cancellation wins
                         ;; after dequeue but before Callable.call.
                         (when (and (not @started) @cancellation)
                           (finish! task @cancellation nil))))]
          (let [rejected? (atom false)]
            (locking (.-lifecycle-lock runtime)
              (if @(.-closed runtime)
                (do
                  (doseq [^Semaphore permit (.-permits task)] (.release permit))
                  (.release ^Semaphore (.-publication-permit task))
                  (throw (IllegalStateException. "runtime is closed")))
                (do
                  (swap! (.-tasks runtime) conj task)
                  (when timeout-ms
                    (reset! deadline
                            (.schedule ^ScheduledExecutorService (.-deadlines runtime)
                                       ^Runnable #(request-cancel! task :timeout)
                                       (long timeout-ms) TimeUnit/MILLISECONDS)))
                  (reset! worker future)
                  (try
                    (execute! pool future)
                    (catch RejectedExecutionException _
                      (reset! rejected? true))))))
            (if @rejected?
              (do (reject! task) {:status :busy :kind kind})
              task)))))))))

(defn then! [^AsyncRuntime runtime ^Task task f]
  (when-not (and (instance? AsyncRuntime runtime) (instance? Task task) (ifn? f))
    (throw (IllegalArgumentException. "then! requires a runtime, task, and function")))
  (io!
   (locking (.-lifecycle-lock runtime)
    (when @(.-closed runtime) (throw (IllegalStateException. "runtime is closed")))
    (when-not (.tryAcquire ^Semaphore (.-callback-admission runtime))
      (throw (ex-info "callback capacity is full"
                      {:tmux/error :busy :tmux/operation :async/then
                       :tmux/phase :admission :tmux/dispatch :not-dispatched})))
    (try
      (let [bindings (get-thread-bindings)
            stage (.thenApplyAsync ^CompletableFuture (.-stage task)
                                   (reify Function
                                     (apply [_ value] (with-bindings bindings (f value))))
                                   ^Executor (.-callbacks runtime))]
        (swap! (.-callback-stages runtime) conj stage)
        (.whenComplete ^CompletableFuture stage
                       (reify BiConsumer
                         (accept [_ _ _]
                           (swap! (.-callback-stages runtime) disj stage)
                           (.release ^Semaphore (.-callback-admission runtime)))))
        (.minimalCompletionStage ^CompletableFuture stage))
      (catch Throwable failure
        (.release ^Semaphore (.-callback-admission runtime))
        (throw failure))))))

(defn- shutdown-completion-if-idle! [^AsyncRuntime runtime]
  (locking (.-lifecycle-lock runtime)
    (when (and (empty? @(.-tasks runtime))
               (empty? @(.-callback-stages runtime)))
      (.shutdown ^ExecutorService (.-completions runtime)))))

(defn close-runtime! [^AsyncRuntime runtime]
  (when-not (instance? AsyncRuntime runtime)
    (throw (IllegalArgumentException. "close-runtime! requires a runtime")))
  (io!
   (let [deadline (+ (System/nanoTime)
                    (.toNanos TimeUnit/MILLISECONDS (.-close-timeout-ms runtime)))
        workers [(.-ordinary runtime) (.-waits runtime)
                 (.-callbacks runtime) (.-deadlines runtime)]
        ^ExecutorService completion (.-completions runtime)
        first-close? (locking (.-lifecycle-lock runtime)
                       (compare-and-set! (.-closed runtime) false true))]
    ;; Never hold the runtime lock while cancellation can finalize a task back
    ;; into the registry.
    (doseq [task @(.-tasks runtime)] (cancel! task))
    (when first-close?
      (let [stages (locking (.-lifecycle-lock runtime)
                     @(.-callback-stages runtime))
            cancelled (CountDownLatch. (count stages))]
        (doseq [^CompletableFuture stage stages]
          (.execute completion
                    ^Runnable #(try
                                 (.completeExceptionally stage (failure :cancelled :unknown))
                                 (finally (.countDown cancelled)))))
        (doseq [^ExecutorService executor workers] (.shutdownNow executor))
        (.await cancelled
                (max 0 (.toMillis TimeUnit/NANOSECONDS
                                  (- deadline (System/nanoTime))))
                TimeUnit/MILLISECONDS)))
    (doseq [^ExecutorService executor workers]
      (.awaitTermination executor
                         (max 0 (.toMillis TimeUnit/NANOSECONDS
                                          (- deadline (System/nanoTime))))
                         TimeUnit/MILLISECONDS))
    (shutdown-completion-if-idle! runtime)
    (when (.isShutdown completion)
      (.awaitTermination completion
                         (max 0 (.toMillis TimeUnit/NANOSECONDS
                                          (- deadline (System/nanoTime))))
                         TimeUnit/MILLISECONDS))
    (when (or (seq @(.-tasks runtime))
              (seq @(.-callback-stages runtime))
              (some #(not (.isTerminated ^ExecutorService %)) workers)
              (not (.isTerminated completion)))
      (throw (ex-info "runtime cleanup is still pending"
                      {:tmux/error :cleanup-pending
                       :tmux/operation :close-runtime
                       :tmux/phase :cleanup
                       :tmux/dispatch :unknown
                       :tasks (count @(.-tasks runtime))
                       :callbacks (count @(.-callback-stages runtime))}))))
    nil))
