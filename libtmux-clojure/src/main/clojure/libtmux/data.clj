(ns libtmux.data
  "Pure captured values, identity, EDN projection, and local selection."
  (:import [io.github.libtmux CapturedServer Client Pane ServerIdentity Session Window]
           [io.github.libtmux.query FilterExpr]
           [io.github.libtmux.snapshot WindowContext]
           [java.time Instant]
           [java.util List UUID]))

(set! *warn-on-reflection* true)

(defn- cardinality-error [operation reason]
  (ex-info "Selection cardinality does not match the requested result"
           {:tmux/error :tmux/cardinality
            :tmux/operation operation
            :tmux/phase :selection
            :tmux/dispatch :not-dispatched
            :cardinality/reason reason}))

(defn one-or-none
  "Returns the only value, nil for none, or throws for multiple values.
  Stops requesting matches once ambiguity is known. Finding zero or one may
  scan all input; chunked producers may realize more. Nil and false are values."
  [values]
  (when-let [items (seq values)]
    (when (next items)
      (throw (cardinality-error :one-or-none :multiple)))
    (first items)))

(defn exactly-one
  "Returns the only value or throws ex-info for zero or multiple values.
  Stops requesting matches once ambiguity is known. Finding zero or one may
  scan all input; chunked producers may realize more. Nil and false are values."
  [values]
  (let [items (seq values)]
    (when-not items
      (throw (cardinality-error :exactly-one :none)))
    (when (next items)
      (throw (cardinality-error :exactly-one :multiple)))
    (first items)))

(defn data
  "Projects captured data to EDN, removing :tmux/ref at every map level.
  Instants become ISO-8601 strings; sequences and Java lists become vectors.
  Unsupported JVM objects throw rather than producing unreadable EDN."
  [value]
  (cond
    (map? value) (into {} (comp (remove #(= :tmux/ref (key %)))
                              (map (fn [[k v]] [(data k) (data v)]))) value)
    (set? value) (into #{} (map data) value)
    (or (sequential? value) (instance? List value)) (mapv data value)
    (instance? Instant value) (str value)
    (or (nil? value) (string? value) (number? value) (boolean? value)
        (keyword? value) (symbol? value) (char? value) (instance? UUID value)) value
    :else (throw (ex-info "Value has no supported EDN projection"
                          {:tmux/error :tmux/invalid-data
                           :tmux/operation :data
                           :tmux/phase :projection
                           :tmux/dispatch :not-dispatched}))))

(defn entity-key
  "Returns a captured physical identity, qualified by endpoint and server PID.
  PID reuse is not a stronger daemon-incarnation guarantee."
  [entry]
  (or (:tmux/identity entry)
      (throw (ex-info "Value has no captured entity identity"
                      {:tmux/error :tmux/invalid-data
                       :tmux/operation :entity-key
                       :tmux/phase :selection
                       :tmux/dispatch :not-dispatched}))))

(defn link-key
  "Returns the physical identity and contextual window placement.
  Only window and pane occurrences carry a link; missing context throws."
  [entry]
  (let [identity (entity-key entry)]
    (if-let [link (:tmux/link entry)]
      [identity link]
      (throw (ex-info "Value has no captured window placement"
                      {:tmux/error :tmux/invalid-data
                       :tmux/operation :link-key
                       :tmux/phase :selection
                       :tmux/dispatch :not-dispatched})))))

(defn same-entity?
  "Compares physical entity identities without acquiring current state."
  [left right]
  (= (entity-key left) (entity-key right)))

(defn same-link?
  "Compares occurrences, including the session, window ID, and index."
  [left right]
  (= (link-key left) (link-key right)))

(defn predicate
  "Adapts a Java FilterExpr to a predicate over captured entries.
  Evaluates the preserved :tmux/ref, including Java relation semantics;
  editing fields in an entry does not change the reference being queried."
  [^FilterExpr expression]
  (when-not (instance? FilterExpr expression)
    (throw (IllegalArgumentException. "Expected a Java FilterExpr")))
  (fn [entry]
    (if-let [reference (:tmux/ref entry)]
      (.test expression reference)
      (throw (ex-info "Detached data has no query reference"
                      {:tmux/error :tmux/invalid-data
                       :tmux/operation :predicate
                       :tmux/phase :selection
                       :tmux/dispatch :not-dispatched})))))

(defn matching
  "Filters captured entries using a Java FilterExpr over their preserved refs.
  One argument returns a transducer; two return a lazy sequence. Both are local
  and preserve order and multiplicity. Detached maps have no query reference."
  ([expression] (filter (predicate expression)))
  ([expression entries] (filter (predicate expression) entries)))

(defn windows
  "Returns the captured window occurrences in a snapshot or session."
  [value]
  (or (:tmux/windows value) (:session/windows value)
      (throw (IllegalArgumentException. "Expected a snapshot or session"))))

(defn panes
  "Returns captured pane occurrences in a snapshot, session, or window."
  [value]
  (or (:tmux/panes value) (:window/panes value)
      (when-let [ws (:session/windows value)]
        (into [] (mapcat :window/panes) ws))
      (throw (IllegalArgumentException. "Expected a snapshot, session, or window"))))

(defn active-window
  "Returns the captured active window of a session, or nil."
  [session]
  (one-or-none (filter :window/active? (windows session))))

(defn active-pane
  "Returns the captured active pane of a window or session, or nil."
  [value]
  (if (contains? value :session/windows)
    (some-> (active-window value) active-pane)
    (one-or-none (filter :pane/active? (panes value)))))

(defn- placement [^WindowContext context]
  {:session/id (.value (.session context))
   :window/id (.value (.window context))
   :window/index (.value (.index context))})

(defn- physical [server kind id]
  (assoc server :tmux/kind kind :tmux/id id))

(defn- pane-entry [server ^Pane pane]
  (let [size (.size pane)
        position (.position pane)
        edges (.edges pane)
        pid (.pid pane)]
    {:tmux/ref pane
     :tmux/identity (physical server :pane (.value (.id pane)))
     :tmux/link (placement (.context (.window pane)))
     :pane/id (.value (.id pane))
     :pane/index (.index pane)
     :pane/active? (.active pane)
     :pane/current-command (.currentCommand pane)
     :pane/floating? (.orElse (.floating pane) nil)
     :pane/width (.width size)
     :pane/height (.height size)
     :pane/left (.left position)
     :pane/top (.top position)
     :pane/title (.title pane)
     :pane/current-path (.currentPathText pane)
     :pane/pid (when (.isPresent pid) (.getAsLong pid))
     :pane/edges {:top? (.top edges) :bottom? (.bottom edges)
                  :left? (.left edges) :right? (.right edges)}}))

(defn- window-entry [server by-link ^Window window]
  (let [link (placement (.context window))
        size (.size window)]
    {:tmux/ref window
     :tmux/identity (physical server :window (.value (.id window)))
     :tmux/link link
     :window/id (.value (.id window))
     :window/index (.value (.index window))
     :window/name (.name window)
     :window/active? (.active window)
     :window/linked? (.linked window)
     :window/width (.width size)
     :window/height (.height size)
     :window/layout (.value (.layout window))
     :window/panes (get by-link link [])}))

(defn- session-entry [server by-session ^Session session]
  {:tmux/ref session
   :tmux/identity (physical server :session (.value (.id session)))
   :session/id (.value (.id session))
   :session/name (.name session)
   :session/attached? (.attached session)
   :session/windows (get by-session (.value (.id session)) [])})

(defn- client-entry [server by-id ^Client client]
  (let [^Session session (.orElse (.session client) nil)
        entry (when session (get by-id (.value (.id session))))]
    {:tmux/ref client
     :tmux/identity (physical server :client (.name client))
     :client/name (.name client)
     :client/session entry
     :client/active-window (when entry (active-window entry))
     :client/active-pane (when entry (active-pane entry))}))

(defn from-capture
  "Converts an authenticated Java CapturedServer to eager persistent values.
  Reads no live getters and issues no commands. Relations share these values;
  window links and pane occurrences retain their captured order and placement."
  [^CapturedServer capture]
  (let [^ServerIdentity server-identity (.identity capture)
        pid (.processId server-identity)
        server {:tmux/realm (.realm server-identity)
                :tmux/server (.server server-identity)
                :tmux/pid (when (.isPresent pid) (.getAsLong pid))}
        snapshot (.snapshot capture)
        ps (mapv #(pane-entry server %) (.panes capture))
        by-link (group-by :tmux/link ps)
        ws (mapv #(window-entry server by-link %) (.windows capture))
        by-session (group-by (comp :session/id :tmux/link) ws)
        ss (mapv #(session-entry server by-session %) (.sessions capture))
        by-id (into {} (map (juxt :session/id identity)) ss)]
    {:tmux/ref (.server capture)
     :tmux/identity (physical server :server (.server server-identity))
     :tmux/captured-at (.capturedAt snapshot)
     :tmux/version (some-> (.orElse (.serverVersion snapshot) nil) str)
     :tmux/sessions ss
     :tmux/windows ws
     :tmux/panes ps
     :tmux/clients (mapv #(client-entry server by-id %) (.clients capture))}))
