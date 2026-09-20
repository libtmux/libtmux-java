(ns libtmux.core
  "Explicit tmux effects. Captured maps retain opaque Java references."
  (:require [libtmux.data :as data])
  (:import [io.github.libtmux Server ServerEndpoint Session Window Pane Client
            SessionSpec SessionSpec$Builder WindowSpec WindowSpec$Builder
            SplitSpec SplitSpec$Builder CaptureSpec Dimensions Options Environment Hooks BufferInfo LibTmuxException
            ObjectDoesNotExistException ServerNotRunningException
            UnsupportedTmuxVersionException]
           [io.github.libtmux.transport TmuxTimeoutException TmuxTransportException]
           [io.github.libtmux.batch BatchResult OperationResult]
           [java.nio.file Path]
           [java.time Duration]))

(def ^:dynamic ^:no-doc *execution-check* nil)

(defn- invalid! [operation]
  (throw (ex-info "Invalid tmux operation arguments"
                  {:tmux/error :tmux/validation :tmux/operation operation
                   :tmux/phase :validation :tmux/dispatch :not-dispatched})))

(defn- require! [operation valid?]
  (when-not valid? (invalid! operation)))

(defn- closed! [operation options schema]
  (require! operation (and (map? options)
                           (every? #(contains? schema %) (keys options))
                           (every? (fn [[k v]] ((get schema k) v)) options)))
  options)

(set! *warn-on-reflection* true)

(defmacro ^:private call [object method classes & args]
  (let [target (gensym "target")]
    `(let [~target ~object]
       (cond
         ~@(mapcat (fn [class]
                     [`(instance? ~class ~target)
                      `(. ~(with-meta target {:tag class}) ~method ~@args)]) classes)
         :else (throw (IllegalArgumentException. "Unexpected typed receiver"))))))

(defn- ref! [operation value classes]
  (require! operation (some #(instance? % value) classes))
  value)

(def ^:private single-effect-operations
  #{:raw :batch :chain :select :kill :link-window :unlink-window :move-window :join-pane
    :resize :apply-layout :send-literal :send-keys :run-shell :detach-client
    :switch-client :set-option :unset-option :set-environment
    :unset-environment :remove-environment :set-hook :append-hook :unset-hook
    :set-buffer :show-buffer :delete-buffer :paste-buffer :buffers
    :signal-channel :capture :expand})

(defn- translate [operation mutation? cause & [composed?]]
  (let [refused? (and (not composed?) (instance? UnsupportedTmuxVersionException cause))
        dispatch (cond
                   refused? :not-dispatched
                   (and (not composed?) (single-effect-operations operation)
                        (instance? TmuxTransportException cause))
                   (case (.name (.outcome ^TmuxTransportException cause))
                     "NOT_DISPATCHED" :not-dispatched
                     "COMPLETE" :complete
                     :unknown)
                   :else :unknown)
        kind (cond
               (instance? ObjectDoesNotExistException cause) :tmux/not-found
               (instance? ServerNotRunningException cause) :tmux/server-unavailable
               (instance? UnsupportedTmuxVersionException cause) :tmux/unsupported-version
               (instance? TmuxTimeoutException cause) :tmux/timeout
               (instance? TmuxTransportException cause) :tmux/transport
               (and (instance? IllegalStateException cause)
                    (= "server is closed" (.getMessage ^Exception cause))) :tmux/closed
               :else :tmux/failure)]
    (ex-info "tmux operation failed"
             {:tmux/error kind :tmux/operation operation
              :tmux/phase (if refused? :validation :unknown)
              :tmux/dispatch dispatch
              :tmux/effects (if (and mutation? (not= :not-dispatched dispatch))
                              :possible :none)} cause)))

(defn- execute! [operation kind f]
  (io!
   (when *execution-check* (*execution-check* {:operation operation :kind kind}))
   (try (f)
        (catch LibTmuxException failure
          (throw (translate operation (= kind :mutation) failure)))
        (catch IllegalArgumentException failure
          (if (and (#{:link-window :move-window :join-pane :switch-client} operation)
                   (#{"handles belong to different tmux servers"
                      "handles belong to different tmux server incarnations"}
                    (.getMessage failure)))
            (throw (ex-info "References belong to different tmux servers"
                            {:tmux/error :tmux/validation :tmux/operation operation
                             :tmux/phase :validation :tmux/dispatch :not-dispatched
                             :tmux/effects :none} failure))
            (throw failure)))
        (catch IllegalStateException failure
          (if (= "server is closed" (.getMessage failure))
            (throw (translate operation (= kind :mutation) failure))
            (throw failure))))))

(defn error-summary
  "Returns fixed error metadata without arguments or cause messages.
  Printing the original exception and its cause chain is not sanitized."
  [failure]
  (select-keys (ex-data failure)
               [:tmux/error :tmux/operation :tmux/phase :tmux/dispatch :tmux/effects]))

(defn- path? [x] (or (string? x) (instance? Path x)))
(defn- path [x] (if (instance? Path x) x (Path/of ^String x (make-array String 0))))
(defn- positive-int? [x] (and (integer? x) (< 0 x Integer/MAX_VALUE)))
(defn- nonnegative-int? [x] (and (integer? x) (<= 0 x Integer/MAX_VALUE)))
(defn- argv? [x] (and (vector? x) (seq x) (every? string? x)))
(defn- environment? [x] (and (map? x) (every? string? (keys x)) (every? string? (vals x))))
(defn- duration? [x] (and (instance? Duration x) (not (.isNegative ^Duration x))
                                (not (.isZero ^Duration x))))

(defn open!
  "Opens local resources for an explicit :socket-path or :socket-name endpoint.
  The returned Server supports with-open; close does not kill tmux."
  [options]
  (closed! :open options {:socket-path path? :socket-name string? :binary string?
                          :config-file path? :timeout duration?})
  (require! :open (= 1 (count (filter #(contains? options %) [:socket-path :socket-name]))))
  (let [endpoint (try
                   (if (:socket-path options)
                     (ServerEndpoint/socketPath (path (:socket-path options)))
                     (ServerEndpoint/namedSocket (:socket-name options)))
                   (catch IllegalArgumentException _ (invalid! :open)))]
   (execute! :open :read
            #(let [builder (Server/builder)]
               (.endpoint builder endpoint)
               (when-let [x (:binary options)] (.binary builder x))
               (when-let [x (:config-file options)] (.configFile builder (path x)))
               (when-let [x (:timeout options)] (.defaultTimeout builder x))
               (.build builder)))))

(defn snapshot! "Acquires an eager captured graph from an explicit server."
  [server]
  (let [^Server server (ref! :snapshot server [Server])]
    (execute! :snapshot :read #(data/from-capture (.capture server)))))

(defn sessions! "Acquires captured sessions; acquisition failure throws." [server]
  (:tmux/sessions (snapshot! server)))
(defn windows! "Acquires captured window occurrences." [server]
  (:tmux/windows (snapshot! server)))
(defn panes! "Acquires captured pane occurrences." [server]
  (:tmux/panes (snapshot! server)))
(defn clients! "Acquires captured clients." [server]
  (:tmux/clients (snapshot! server)))

(defn find-session! "Finds a session by exact name; absence returns nil." [server name]
  (require! :find-session (string? name))
  (data/one-or-none (filter #(= name (:session/name %)) (sessions! server))))

(defn- captured-reference [snapshot reference context]
  (let [entries (cond (instance? Server reference) [snapshot]
                      (instance? Session reference) (:tmux/sessions snapshot)
                      (instance? Window reference) (:tmux/windows snapshot)
                      (instance? Pane reference) (:tmux/panes snapshot)
                      (instance? Client reference) (:tmux/clients snapshot))]
    (data/one-or-none
     (filter #(and (= reference (:tmux/ref %))
                   (if (instance? Pane reference)
                     (= (or context (.context (.window ^Pane reference)))
                        (.context (.window ^Pane (:tmux/ref %)))) true)) entries))))

(defn- refresh-reference! [reference & [context]]
  (let [^Server server (if (instance? Server reference) reference (call reference server [Session Window Pane Client]))]
    (captured-reference (data/from-capture (.capture server)) reference context)))

(defn refresh!
  "Reacquires an explicit reference, retaining window and pane placement.
  Returns nil if that occurrence disappeared; server failure throws."
  [entry]
  (let [reference (ref! :refresh entry [Server Session Window Pane Client])]
    (execute! :refresh :read #(refresh-reference! reference))))

(def ^:private creation-schema
  {:name string? :command argv? :directory path? :environment environment?})

(defn- build-spec [operation options builder schema]
  (closed! operation options schema)
  (require! operation (not (and (:cells options) (:percent options))))
  (require! operation (not (and (:empty? options) (:command options))))
  (try
    (when-let [x (:name options)] (call builder named [SessionSpec$Builder WindowSpec$Builder] x))
    (when-let [x (:window-name options)] (.firstWindowNamed ^SessionSpec$Builder builder x))
    (when-let [x (:command options)]
      (call builder running [SessionSpec$Builder WindowSpec$Builder SplitSpec$Builder]
            (into-array String (if (= 1 (count x))
                                 (into ["/bin/sh" "-c" "exec \"$@\"" "libtmux-clojure"] x)
                                 x))))
    (when-let [x (:directory options)]
      (call builder in [SessionSpec$Builder WindowSpec$Builder SplitSpec$Builder] (path x)))
    (when-let [x (:environment options)]
      (call builder environment [SessionSpec$Builder WindowSpec$Builder SplitSpec$Builder] x))
    (when-let [x (:index options)] (.atIndex ^WindowSpec$Builder builder (int x)))
    (when (:detached? options) (call builder detached [WindowSpec$Builder SplitSpec$Builder]))
    (when (:empty? options) (.empty ^SplitSpec$Builder builder))
    (when (:full-window? options) (.fullWindow ^SplitSpec$Builder builder))
    (when (:zoomed? options) (.zoomed ^SplitSpec$Builder builder))
    (when-let [x (:direction options)]
      (case x
        :below (.below ^SplitSpec$Builder builder)
        :above (.above ^SplitSpec$Builder builder)
        :right (.toRight ^SplitSpec$Builder builder)
        :left (.toLeft ^SplitSpec$Builder builder)))
    (when-let [x (:cells options)] (.cells ^SplitSpec$Builder builder (int x)))
    (when-let [x (:percent options)] (.percent ^SplitSpec$Builder builder (int x)))
    (call builder build [SessionSpec$Builder WindowSpec$Builder SplitSpec$Builder])
    (catch IllegalArgumentException cause
      (throw (ex-info "Invalid tmux specification"
                      {:tmux/error :tmux/validation :tmux/operation operation
                       :tmux/phase :validation :tmux/dispatch :not-dispatched} cause)))))

(defn- capture-after-mutation! [operation reference & [context]]
  (try (refresh-reference! reference context)
       (catch LibTmuxException failure (throw (translate operation true failure true)))
       (catch clojure.lang.ExceptionInfo failure
         (if (:tmux/error (ex-data failure))
           (throw (translate operation true failure true))
           (throw failure)))))

(defn new-session! "Creates a detached session and returns fresh captured data."
  [server options]
  (let [^Server server (ref! :new-session server [Server])
        ^SessionSpec spec (build-spec :new-session options (SessionSpec/builder)
                                      (assoc creation-schema :window-name string?))]
    (execute! :new-session :mutation #(capture-after-mutation! :new-session (.newSession server spec)))))

(defn new-window! "Creates a window and returns fresh captured data." [session options]
  (let [^Session session (ref! :new-window session [Session])
        ^WindowSpec spec (build-spec :new-window options (WindowSpec/builder)
                                     (assoc creation-schema :index nonnegative-int? :detached? boolean?))]
    (execute! :new-window :mutation #(capture-after-mutation! :new-window (.newWindow session spec)))))

(defn split-pane! "Splits a pane using argv, never implicit shell interpolation." [pane options]
  (let [^Pane pane (ref! :split-pane pane [Pane])
        ^SplitSpec spec (build-spec :split-pane options (SplitSpec/builder)
                                   (merge (dissoc creation-schema :name)
                                          {:direction #{:below :above :right :left}
                                           :cells positive-int? :percent #(and (integer? %) (<= 1 % 100))
                                           :detached? boolean? :empty? boolean?
                                           :full-window? boolean? :zoomed? boolean?}))]
    (execute! :split-pane :mutation #(capture-after-mutation! :split-pane (.split pane spec) (.context (.window pane))))))

(defn rename! "Renames a session or window and captures its current data." [value name]
  (require! :rename (string? name))
  (let [reference (ref! :rename value [Session Window])]
    (execute! :rename :mutation #(capture-after-mutation! :rename (call reference rename [Session Window] name)))))

(defn select! "Selects the preserved window or pane occurrence." [value]
  (let [reference (ref! :select value [Window Pane])]
    (execute! :select :mutation #(call reference select [Window Pane]))))

(defn kill! "Kills the explicitly supplied session, window, or pane." [value]
  (let [reference (ref! :kill value [Session Window Pane])]
    (execute! :kill :mutation #(call reference kill [Session Window Pane]))))

(defn kill-server! "Kills the explicit tmux server; close only releases local resources." [server]
  (let [^Server server (ref! :kill-server server [Server])]
    (execute! :kill-server :mutation #(.killServer server))))

(defn close! "Releases the supplied Server's local resources without killing tmux." [server]
  (let [^Server server (ref! :close server [Server])]
    (execute! :close :read #(.close server))))

(defn link-window! "Links a window into a session." [window session]
  (let [^Window window (ref! :link-window window [Window])
        ^Session session (ref! :link-window session [Session])]
    (execute! :link-window :mutation #(.linkTo window session))))
(defn unlink-window! "Unlinks the preserved window occurrence." [window]
  (let [^Window window (ref! :unlink-window window [Window])]
    (execute! :unlink-window :mutation #(.unlink window))))
(defn move-window! "Moves a window to a session and explicit index." [window session index]
  (require! :move-window (nonnegative-int? index))
  (let [^Window window (ref! :move-window window [Window])
        ^Session session (ref! :move-window session [Session])]
    (execute! :move-window :mutation #(.moveTo window session (int index)))))
(defn join-pane! "Joins a pane to a window." [pane window]
  (let [^Pane pane (ref! :join-pane pane [Pane]) ^Window window (ref! :join-pane window [Window])]
    (execute! :join-pane :mutation #(.joinTo pane window))))
(defn resize! "Resizes a window or pane to positive cell dimensions." [value width height]
  (require! :resize (and (positive-int? width) (positive-int? height)))
  (let [reference (ref! :resize value [Window Pane])]
    (execute! :resize :mutation #(call reference resizeTo [Window Pane] (Dimensions. (int width) (int height))))))
(defn apply-layout! "Applies an explicit tmux layout string." [window layout]
  (require! :apply-layout (string? layout))
  (let [^Window window (ref! :apply-layout window [Window])]
    (execute! :apply-layout :mutation #(.applyLayout window ^String layout))))

(defn send-literal! "Sends literal text; completion does not mean application readiness." [pane text]
  (require! :send-literal (string? text))
  (let [^Pane pane (ref! :send-literal pane [Pane])]
    (execute! :send-literal :mutation #(.sendLiteral pane [text]))))
(defn send-keys! "Sends an explicit vector of tmux key names." [pane keys]
  (require! :send-keys (argv? keys))
  (let [^Pane pane (ref! :send-keys pane [Pane])]
    (execute! :send-keys :mutation #(.sendKeys pane keys))))

(defn capture!
  "Captures pane lines; :from :history includes scrollback."
  ([pane] (capture! pane {}))
  ([pane options]
   (closed! :capture options {:from #(or (= :history %) (and (integer? %) (<= Integer/MIN_VALUE % Integer/MAX_VALUE)))
                             :to #(and (integer? %) (<= Integer/MIN_VALUE % Integer/MAX_VALUE))
                             :join-wrapped? boolean? :escape-sequences? boolean?})
   (let [^Pane pane (ref! :capture pane [Pane]) builder (CaptureSpec/builder)]
     (when-let [x (:from options)] (if (= :history x) (.fromStartOfHistory builder) (.from builder (int x))))
     (when-let [x (:to options)] (.to builder (int x)))
     (when (:join-wrapped? options) (.joiningWrappedLines builder))
     (when (:escape-sequences? options) (.withEscapeSequences builder))
     (let [spec (.build builder)] (execute! :capture :read #(vec (.capture pane spec)))))))

(defn expand! "Evaluates an explicit tmux format at the supplied scope." [value format]
  (require! :expand (string? format))
  (let [reference (ref! :expand value [Server Session Window Pane])]
    (execute! :expand :read #(call reference expand [Server Session Window Pane] format))))

(defn raw! "Runs explicit tmux argv and returns exit status and streams; no shell." [server argv]
  (require! :raw (argv? argv))
  (let [^Server server (ref! :raw server [Server])]
    (execute! :raw :mutation #(let [result (.cmd server ^java.util.List argv)]
                               {:exit-code (.exitCode result) :stdout (vec (.stdout result)) :stderr (vec (.stderr result))}))))

(defn run-shell! "Runs an explicitly requested shell command on the server." [server command]
  (require! :run-shell (string? command))
  (let [^Server server (ref! :run-shell server [Server])]
    (execute! :run-shell :mutation #(.runShell server command))))

(defn await-text! "Waits within a positive Duration; returns a keyword describing the outcome." [pane text timeout]
  (require! :await-text (and (string? text) (duration? timeout)))
  (let [^Pane pane (ref! :await-text pane [Pane])]
    (execute! :await-text :wait
              #(case (.name (.awaitText pane text timeout))
                 "APPEARED" :appeared
                 "PRESENT_AT_ENTRY" :present-at-entry
                 "TIMED_OUT" :timed-out
                 "SERVER_GONE" :server-gone))))

(defn detach-client! "Detaches the explicit client." [client]
  (let [^Client client (ref! :detach-client client [Client])]
    (execute! :detach-client :mutation #(.detach client))))
(defn switch-client! "Switches an explicit client to a session." [client session]
  (let [^Client client (ref! :switch-client client [Client]) ^Session session (ref! :switch-client session [Session])]
    (execute! :switch-client :mutation #(.switchTo client session))))

(defn- ^Options options-reference [reference]
  (call reference options [Server Session Window Pane]))
(defn- ^Environment environment-reference [reference]
  (call reference environment [Server Session]))
(defn- ^Hooks hooks-reference [reference]
  (call reference hooks [Server Session Window Pane]))

(defn options!
  "Reads :local options by default, or :effective inherited options."
  ([value] (options! value :local))
  ([value scope]
   (require! :options (#{:local :effective} scope))
   (let [reference (ref! :options value [Server Session Window Pane])]
     (execute! :options :read
               #(into {} (let [^Options options (call reference options [Server Session Window Pane])]
                           (if (= scope :local) (.all options) (.effective options))))))))
(defn set-option! "Sets a literal option value at the explicit scope." [value name text]
  (require! :set-option (and (string? name) (string? text)))
  (let [reference (ref! :set-option value [Server Session Window Pane])]
    (execute! :set-option :mutation #(.set (options-reference reference) ^String name ^String text))))
(defn unset-option! "Removes a local option override." [value name]
  (require! :unset-option (string? name))
  (let [reference (ref! :unset-option value [Server Session Window Pane])]
    (execute! :unset-option :mutation #(.unset (options-reference reference) name))))

(defn environment!
  "Returns values and explicitly removed names, preserving removal versus absence."
  ([value] (environment! value :local))
  ([value scope]
   (require! :environment (#{:local :effective} scope))
   (let [reference (ref! :environment value [Server Session])]
     (execute! :environment :read
               #(let [^Environment environment (call reference environment [Server Session])]
                  {:values (into {} (if (= scope :local) (.all environment) (.effective environment)))
                   :removed (set (.removed environment))})))))
(defn set-environment! "Sets a literal environment value." [value name text]
  (require! :set-environment (and (string? name) (string? text)))
  (let [reference (ref! :set-environment value [Server Session])]
    (execute! :set-environment :mutation #(.set (environment-reference reference) name text))))
(defn unset-environment! "Removes the local environment entry, allowing inheritance." [value name]
  (require! :unset-environment (string? name))
  (let [reference (ref! :unset-environment value [Server Session])]
    (execute! :unset-environment :mutation #(.unset (environment-reference reference) name))))
(defn remove-environment! "Marks an environment name removed for new child processes." [value name]
  (require! :remove-environment (string? name))
  (let [reference (ref! :remove-environment value [Server Session])]
    (execute! :remove-environment :mutation #(.remove (environment-reference reference) name))))

(defn hooks! "Returns hook names and their ordered command vectors." [value]
  (let [reference (ref! :hooks value [Server Session Window Pane])]
    (execute! :hooks :read #(into {} (map (fn [[k v]] [k (vec v)]))
                                  (.all (hooks-reference reference))))))
(defn set-hook! "Replaces a hook with one explicit tmux argv command." [value event argv]
  (require! :set-hook (and (string? event) (argv? argv)))
  (let [reference (ref! :set-hook value [Server Session Window Pane])]
    (execute! :set-hook :mutation #(.set (hooks-reference reference) ^String event ^java.util.List argv))))
(defn append-hook! "Appends one explicit tmux argv command to a hook." [value event argv]
  (require! :append-hook (and (string? event) (argv? argv)))
  (let [reference (ref! :append-hook value [Server Session Window Pane])]
    (execute! :append-hook :mutation #(.append (hooks-reference reference) ^String event ^java.util.List argv))))
(defn unset-hook! "Removes an explicit hook." [value event]
  (require! :unset-hook (string? event))
  (let [reference (ref! :unset-hook value [Server Session Window Pane])]
    (execute! :unset-hook :mutation #(.unset (hooks-reference reference) event))))

(defn set-buffer! "Stores literal buffer contents." [server name text]
  (require! :set-buffer (and (string? name) (string? text)))
  (let [^Server server (ref! :set-buffer server [Server])]
    (execute! :set-buffer :mutation #(.set (.buffers server) name text))))
(defn show-buffer! "Reads literal buffer contents." [server name]
  (require! :show-buffer (string? name))
  (let [^Server server (ref! :show-buffer server [Server])]
    (execute! :show-buffer :read #(.show (.buffers server) name))))
(defn delete-buffer! "Deletes an explicit buffer." [server name]
  (require! :delete-buffer (string? name))
  (let [^Server server (ref! :delete-buffer server [Server])]
    (execute! :delete-buffer :mutation #(.delete (.buffers server) name))))
(defn paste-buffer! "Pastes an explicit buffer into a pane." [pane name]
  (require! :paste-buffer (string? name))
  (let [^Pane pane (ref! :paste-buffer pane [Pane])]
    (execute! :paste-buffer :mutation #(.pasteBuffer pane name))))


(defn buffers! "Lists captured buffer names and byte sizes." [server]
  (let [^Server server (ref! :buffers server [Server])]
    (execute! :buffers :read
              #(mapv (fn [^BufferInfo buffer]
                       {:buffer/name (.name buffer) :buffer/size (.size buffer)})
                     (.list (.buffers server))))))


(defn await-channel!
  "Waits on an explicit tmux channel while reserving capacity for its release.
  Returns :signalled, :timed-out, or :server-gone; interruption propagates."
  [server name timeout]
  (require! :await-channel (and (string? name) (duration? timeout)))
  (let [^Server server (ref! :await-channel server [Server])]
    (execute! :await-channel :wait
              #(case (.name (.awaitReservingCapacity (.channel server name) timeout))
                 "SIGNALLED" :signalled
                 "TIMED_OUT" :timed-out
                 "SERVER_GONE" :server-gone))))

(defn signal-channel! "Signals an explicit tmux channel." [server name]
  (require! :signal-channel (string? name))
  (let [^Server server (ref! :signal-channel server [Server])]
    (execute! :signal-channel :mutation #(.signal (.channel server name)))))


(defn- operation-result [index ^OperationResult result]
  {:operation/index index
   :operation/argv (vec (.argv result))
   :operation/outcome (case (.name (.outcome result))
                        "COMPLETE" :complete
                        "FAILED" :failed
                        "SKIPPED" :skipped
                        "UNKNOWN" :unknown)
   :operation/stdout (vec (.stdout result))
   :operation/stderr (vec (.stderr result))})

(defn- group! [operation server operations options]
  (closed! operation options {:kind #{:mutation :wait}})
  (require! operation (and (vector? operations) (every? argv? operations)))
  (require! operation
            (or (= :wait (:kind options))
                (not-any? (fn [argv]
                            (and (#{"wait-for" "wait"} (first argv))
                                 (not-any? #{"-S" "-U"} (rest argv))))
                          operations)))
  (let [^Server server (ref! operation server [Server])
        waiting? (= :wait (:kind options))]
    (execute!
     operation (if waiting? :wait :mutation)
     #(try
        (let [^BatchResult result
              (if (= operation :batch)
                (let [builder (if waiting? (.batchReservingCapacity server) (.batch server))]
                  (doseq [argv operations] (.add builder ^java.util.List argv))
                  (.run builder))
                (let [builder (if waiting? (.chainReservingCapacity server) (.chain server))]
                  (doseq [argv operations] (.then builder ^java.util.List argv))
                  (.run builder)))]
          (mapv operation-result (range) (.operations result)))
        (catch TmuxTransportException cause
          (let [error (translate operation true cause)
                outcome (if (= :not-dispatched (:tmux/dispatch (ex-data error)))
                          :skipped :unknown)]
            (throw (ex-info "tmux command group failed"
                            (assoc (ex-data error) :tmux/results
                                   (mapv (fn [index argv]
                                           {:operation/index index :operation/argv argv
                                            :operation/outcome outcome
                                            :operation/stdout [] :operation/stderr []})
                                         (range) operations))
                            cause))))))))

(defn batch!
  "Runs independent argv commands using a fresh Java Batch and returns positions.
  Supply explicit targets. Failed/skipped outcomes are data; transport failure
  throws with :tmux/results. Use {:kind :wait} for groups needing a release,
  including blocking shell commands. No rollback or retry safety is promised."
  ([server operations] (batch! server operations {}))
  ([server operations options] (group! :batch server operations options)))

(defn chain!
  "Runs dependent argv commands with tmux's implicit target between positions.
  Uses a fresh Java CommandChain. Failed/skipped outcomes are data; transport
  failure throws with :tmux/results. Use {:kind :wait} for groups needing a release,
  including blocking shell commands. No rollback or retry safety is promised."
  ([server operations] (chain! server operations {}))
  ([server operations options] (group! :chain server operations options)))
