# libtmux-clojure API

Generated from public namespace and var metadata; do not edit by hand.
Each heading links to its current Clojure source.

Read the [guide](clojure.md) for runnable programs and ownership.
The [captured-value reference](clojure-reference.md) documents map keys.
Functions ending in `!` may cross the external-state boundary.

## Namespaces

- [`libtmux.core`](#libtmuxcore)
- [`libtmux.data`](#libtmuxdata)
- [`libtmux.async`](#libtmuxasync)
- [`libtmux.control`](#libtmuxcontrol)

## [`libtmux.core`][libtmux.core]

Explicit tmux effects. Captured maps retain opaque Java references.

### [`libtmux.core/append-hook!`][libtmux.core]

`(append-hook! value event argv)`

Appends one explicit tmux argv command to a hook.

### [`libtmux.core/apply-layout!`][libtmux.core]

`(apply-layout! window layout)`

Applies an explicit tmux layout string.

### [`libtmux.core/await-channel!`][libtmux.core]

`(await-channel! server name timeout)`

Waits on an explicit tmux channel while reserving capacity for its release.
Returns :signalled, :timed-out, or :server-gone; interruption propagates.

### [`libtmux.core/await-text!`][libtmux.core]

`(await-text! pane text timeout)`

Waits within a positive Duration; returns a keyword describing the outcome.

### [`libtmux.core/batch!`][libtmux.core]

`(batch! server operations)`

`(batch! server operations options)`

Runs independent argv commands using a fresh Java Batch and returns positions.
Supply explicit targets. Failed/skipped outcomes are data; transport failure
throws with :tmux/results. Use {:kind :wait} for groups needing a release,
including blocking shell commands. No rollback or retry safety is promised.

### [`libtmux.core/buffers!`][libtmux.core]

`(buffers! server)`

Lists captured buffer names and byte sizes.

### [`libtmux.core/capture!`][libtmux.core]

`(capture! pane)`

`(capture! pane options)`

Captures pane lines; :from :history includes scrollback.

### [`libtmux.core/chain!`][libtmux.core]

`(chain! server operations)`

`(chain! server operations options)`

Runs dependent argv commands with tmux's implicit target between positions.
Uses a fresh Java CommandChain. Failed/skipped outcomes are data; transport
failure throws with :tmux/results. Use {:kind :wait} for groups needing a
release, including blocking shell commands. No rollback or retry safety is
promised.

### [`libtmux.core/clients!`][libtmux.core]

`(clients! server)`

Acquires captured clients.

### [`libtmux.core/close!`][libtmux.core]

`(close! server)`

Releases the supplied Server's local resources without killing tmux.

### [`libtmux.core/delete-buffer!`][libtmux.core]

`(delete-buffer! server name)`

Deletes an explicit buffer.

### [`libtmux.core/detach-client!`][libtmux.core]

`(detach-client! client)`

Detaches the explicit client.

### [`libtmux.core/environment!`][libtmux.core]

`(environment! value)`

`(environment! value scope)`

Returns values and explicitly removed names, preserving removal versus absence.

### [`libtmux.core/error-summary`][libtmux.core]

`(error-summary failure)`

Returns fixed error metadata without arguments or cause messages.
Printing the original exception and its cause chain is not sanitized.

### [`libtmux.core/expand!`][libtmux.core]

`(expand! value format)`

Evaluates an explicit tmux format at the supplied scope.

### [`libtmux.core/find-session!`][libtmux.core]

`(find-session! server name)`

Finds a session by exact name; absence returns nil.

### [`libtmux.core/hooks!`][libtmux.core]

`(hooks! value)`

Returns hook names and their ordered command vectors.

### [`libtmux.core/join-pane!`][libtmux.core]

`(join-pane! pane window)`

Joins a pane to a window.

### [`libtmux.core/kill!`][libtmux.core]

`(kill! value)`

Kills the explicitly supplied session, window, or pane.

### [`libtmux.core/kill-server!`][libtmux.core]

`(kill-server! server)`

Kills the explicit tmux server; close only releases local resources.

### [`libtmux.core/link-window!`][libtmux.core]

`(link-window! window session)`

Links a window into a session.

### [`libtmux.core/move-window!`][libtmux.core]

`(move-window! window session index)`

Moves a window to a session and explicit index.

### [`libtmux.core/new-session!`][libtmux.core]

`(new-session! server options)`

Creates a detached session and returns a fresh captured session map.
Options are :name, :window-name, :command (nonempty argv vector), :directory
(path), and :environment (string keys and values). Unknown keys are rejected.
A recapture failure can occur after creation; inspect error-summary before
retrying a mutation.

### [`libtmux.core/new-window!`][libtmux.core]

`(new-window! session options)`

Creates a window and returns a fresh captured window occurrence.
Takes a Session ref and :name, :command (argv), :directory, :environment,
:index (nonnegative integer), and :detached? options.

### [`libtmux.core/open!`][libtmux.core]

`(open! options)`

Opens local resources for an explicit :socket-path or :socket-name endpoint.
Supply exactly one endpoint. Paths accept String or java.nio.file.Path.
Optional :binary is a command name or path; :config-file is a path;
:timeout is a positive java.time.Duration. Unknown keys are rejected.
Returns a Server for with-open; closing it does not kill tmux.

### [`libtmux.core/options!`][libtmux.core]

`(options! value)`

`(options! value scope)`

Reads :local options by default, or :effective inherited options.

### [`libtmux.core/panes!`][libtmux.core]

`(panes! server)`

Acquires captured pane occurrences.

### [`libtmux.core/paste-buffer!`][libtmux.core]

`(paste-buffer! pane name)`

Pastes an explicit buffer into a pane.

### [`libtmux.core/raw!`][libtmux.core]

`(raw! server argv)`

Runs explicit tmux argv and returns exit status and streams; no shell.

### [`libtmux.core/refresh!`][libtmux.core]

`(refresh! entry)`

Reacquires an explicit reference, retaining window and pane placement.
Returns nil if that occurrence disappeared; server failure throws.

### [`libtmux.core/remove-environment!`][libtmux.core]

`(remove-environment! value name)`

Marks an environment name removed for new child processes.

### [`libtmux.core/rename!`][libtmux.core]

`(rename! value name)`

Renames a session or window and captures its current data.

### [`libtmux.core/resize!`][libtmux.core]

`(resize! value width height)`

Resizes a window or pane to positive cell dimensions.

### [`libtmux.core/run-shell!`][libtmux.core]

`(run-shell! server command)`

Runs an explicitly requested shell command on the server.

### [`libtmux.core/select!`][libtmux.core]

`(select! value)`

Selects the preserved window or pane occurrence.

### [`libtmux.core/send-keys!`][libtmux.core]

`(send-keys! pane keys)`

Sends an explicit vector of tmux key names.

### [`libtmux.core/send-literal!`][libtmux.core]

`(send-literal! pane text)`

Sends literal text; completion does not mean application readiness.

### [`libtmux.core/sessions!`][libtmux.core]

`(sessions! server)`

Acquires captured sessions; acquisition failure throws.

### [`libtmux.core/set-buffer!`][libtmux.core]

`(set-buffer! server name text)`

Stores literal buffer contents.

### [`libtmux.core/set-environment!`][libtmux.core]

`(set-environment! value name text)`

Sets a literal environment value.

### [`libtmux.core/set-hook!`][libtmux.core]

`(set-hook! value event argv)`

Replaces a hook with one explicit tmux argv command.

### [`libtmux.core/set-option!`][libtmux.core]

`(set-option! value name text)`

Sets a literal option value at the explicit scope.

### [`libtmux.core/show-buffer!`][libtmux.core]

`(show-buffer! server name)`

Reads literal buffer contents.

### [`libtmux.core/signal-channel!`][libtmux.core]

`(signal-channel! server name)`

Signals an explicit tmux channel.

### [`libtmux.core/snapshot!`][libtmux.core]

`(snapshot! server)`

Acquires an eager captured graph from an explicit server.

### [`libtmux.core/split-pane!`][libtmux.core]

`(split-pane! pane options)`

Splits a Pane ref and returns a fresh captured pane occurrence.
Options are :command (argv), :directory, :environment, :direction
(:below, :above, :right, :left), :cells or :percent (mutually exclusive),
:detached?, :empty?, :full-window?, and :zoomed?. :empty? excludes :command.
A single argv element is executed literally, without shell interpolation.

### [`libtmux.core/switch-client!`][libtmux.core]

`(switch-client! client session)`

Switches an explicit client to a session.

### [`libtmux.core/unlink-window!`][libtmux.core]

`(unlink-window! window)`

Unlinks the preserved window occurrence.

### [`libtmux.core/unset-environment!`][libtmux.core]

`(unset-environment! value name)`

Removes the local environment entry, allowing inheritance.

### [`libtmux.core/unset-hook!`][libtmux.core]

`(unset-hook! value event)`

Removes an explicit hook.

### [`libtmux.core/unset-option!`][libtmux.core]

`(unset-option! value name)`

Removes a local option override.

### [`libtmux.core/windows!`][libtmux.core]

`(windows! server)`

Acquires captured window occurrences.

## [`libtmux.data`][libtmux.data]

Pure captured values, identity, EDN projection, and local selection.

### [`libtmux.data/active-pane`][libtmux.data]

`(active-pane value)`

Returns the captured active pane of a window or session, or nil.

### [`libtmux.data/active-window`][libtmux.data]

`(active-window session)`

Returns the captured active window of a session, or nil.

### [`libtmux.data/data`][libtmux.data]

`(data value)`

Projects captured data to EDN, removing :tmux/ref at every map level.
Instants become ISO-8601 strings; sequences and Java lists become vectors.
Unsupported JVM objects throw rather than producing unreadable EDN.

### [`libtmux.data/entity-key`][libtmux.data]

`(entity-key entry)`

Returns a captured physical identity, qualified by endpoint and server PID.
PID reuse is not a stronger daemon-incarnation guarantee.

### [`libtmux.data/exactly-one`][libtmux.data]

`(exactly-one values)`

Returns the only value or throws ex-info for zero or multiple values.
Stops requesting matches once ambiguity is known. Finding zero or one may
scan all input; chunked producers may realize more. Nil and false are values.

### [`libtmux.data/from-capture`][libtmux.data]

`(from-capture capture)`

Converts an authenticated Java CapturedServer to eager persistent values.
Reads no live getters and issues no commands. Relations share these values;
window links and pane occurrences retain their captured order and placement.

### [`libtmux.data/link-key`][libtmux.data]

`(link-key entry)`

Returns the physical identity and contextual window placement.
Only window and pane occurrences carry a link; missing context throws.

### [`libtmux.data/matching`][libtmux.data]

`(matching expression)`

`(matching expression entries)`

Filters captured entries using a Java FilterExpr over their preserved refs.
One argument returns a transducer; two return a lazy sequence. Both are local
and preserve order and multiplicity. Detached maps have no query reference.

### [`libtmux.data/one-or-none`][libtmux.data]

`(one-or-none values)`

Returns the only value, nil for none, or throws for multiple values.
Stops requesting matches once ambiguity is known. Finding zero or one may
scan all input; chunked producers may realize more. Nil and false are values.

### [`libtmux.data/panes`][libtmux.data]

`(panes value)`

Returns captured pane occurrences in a snapshot, session, or window.

### [`libtmux.data/predicate`][libtmux.data]

`(predicate expression)`

Adapts a Java FilterExpr to a predicate over captured entries.
Evaluates the preserved :tmux/ref, including Java relation semantics;
editing fields in an entry does not change the reference being queried.

### [`libtmux.data/same-entity?`][libtmux.data]

`(same-entity? left right)`

Compares physical entity identities without acquiring current state.

### [`libtmux.data/same-link?`][libtmux.data]

`(same-link? left right)`

Compares occurrences, including the session, window ID, and index.

### [`libtmux.data/windows`][libtmux.data]

`(windows value)`

Returns the captured window occurrences in a snapshot or session.

## [`libtmux.async`][libtmux.async]

Bounded owned workers, cancellable tasks, and CompletionStage interop.

### [`libtmux.async/cancel!`][libtmux.async]

`(cancel! task)`

Requests cancellation. The first accepted cancellation reason wins.

### [`libtmux.async/close-runtime!`][libtmux.async]

`(close-runtime! runtime)`

Cancels owned tasks and callbacks and shuts down the runtime's executors.
Waits within :close-timeout-ms. Interruption-resistant work can outlive that
budget; it cannot be forcibly stopped. Pending cleanup throws ex-info with
:tmux/error :cleanup-pending. Safe to call again; returns nil after cleanup.

### [`libtmux.async/completion-stage`][libtmux.async]

`(completion-stage task)`

Returns a read-only CompletionStage completed after worker cleanup.
Completing or cancelling a derived CompletableFuture does not control the
task; use cancel!. Caller-attached callbacks follow Java stage scheduling.

### [`libtmux.async/runtime!`][libtmux.async]

`(runtime!)`

`(runtime! options)`

Creates a closeable runtime owning bounded work, wait and callback lanes.
Options are :max-running (default 4, minimum 2), :queue-limit (default 64,
positive), and :close-timeout-ms (default 900, positive). One running slot is
reserved for waits so ordinary work can release them. Closing cancels owned
work and waits within the close budget; it never closes borrowed servers.

### [`libtmux.async/submit!`][libtmux.async]

`(submit! runtime thunk)`

`(submit! runtime options thunk)`

Submits a zero-argument function with the caller's dynamic bindings.
Options are :kind (:ordinary by default, or :wait) and a positive
:timeout-ms deadline including queue time. Returns a dereferenceable Task
or {:status :busy :kind kind} when admission is full. Timed deref does not
cancel a task; cancel! or its deadline interrupts owned work. Effects that
wait require :wait. Exceptions retain dispatch certainty and partial results.

### [`libtmux.async/task-kind`][libtmux.async]

`(task-kind task)`

Returns the declared :ordinary or :wait lane without waiting for the task.

### [`libtmux.async/task-state`][libtmux.async]

`(task-state task)`

Reads task progress: :queued, :running, :cancelling, :cleanup-pending,
or :terminal. A cancellation request is not proof cleanup has finished.

### [`libtmux.async/then!`][libtmux.async]

`(then! runtime task f)`

Returns a read-only CompletionStage applying f after successful task cleanup.
Runs on the runtime's bounded callback lane with the caller's dynamic
bindings. Full callback admission throws ex-info with :tmux/error :busy.
Closing the runtime cancels queued callbacks and interrupts active ones.

## [`libtmux.control`][libtmux.control]

Explicit control replies and owned, bounded pane-output subscriptions.
Control needs tmux 3.7 or newer; an older daemon is refused before
any client is attached, with :tmux/error :tmux/unsupported-feature.

### [`libtmux.control/attach!`][libtmux.control]

`(attach! session)`

`(attach! session timeout)`

Attaches an owned control client to an explicit captured Session reference.
Close releases this client, never its server. Use a dedicated connection
when cancellation must not affect other requests and subscriptions.

### [`libtmux.control/close!`][libtmux.control]

`(close! resource)`

Closes a connection or subscription. Closing a subscription leaves its
borrowed connection open; closing a connection affects all its subscribers.

### [`libtmux.control/next!`][libtmux.control]

`(next! stream)`

`(next! stream timeout)`

Reads one event, gap, timeout, or terminal map; never uses nil for termination.
A :gap names the events dropped since the previous read and precedes the
events that remain. The optional nonnegative Duration includes waiting for
another reader. Sequence numbers order the events of this stream. Close wakes
a reader. Termination by the client keeps already delivered events readable;
an intentional close discards them.

### [`libtmux.control/send!`][libtmux.control]

`(send! connection argv)`

`(send! connection argv timeout)`

Sends argv and returns protocol status, lines, and unverified completion.
A complete reply does not prove deferred work finished. Cancelling an active
send can end this connection and its other requests and subscriptions.

### [`libtmux.control/subscribe!`][libtmux.control]

`(subscribe! connection)`

`(subscribe! connection options)`

Creates a closeable output subscription borrowing its connection.
:encoding is :bytes (caller-owned byte arrays) or :utf-8 (incremental text
per pane, reset after this subscription's gaps; malformed bytes use \xHH).
:decoder-capacity bounds partial-pane state (default 1024); an eviction is
reported with :decoder/reset :capacity and a cumulative :decoder/resets.
:capacity bounds queued events; :max-bytes bounds their unescaped payload
(default 16 MiB).
Oldest events are dropped until both bounds fit; an oversized event drops
the queued prefix and itself. The next read is a :gap with byte/event loss.
Readers compete; create separate subscriptions for independent fan-out.

### [`libtmux.control/terminal`][libtmux.control]

`(terminal stream)`

Returns the single recorded stream outcome, or nil until a reader drains it.
:reason is :closed, :completed, :control-exit, :transport-failure,
:protocol-failure, or :unknown. A control exit does not prove daemon death;
:cause preserves the original exception and may contain sensitive content.
Text streams include :trailing-text keyed by pane ID for
incomplete final bytes rendered as \xHH.

[libtmux.core]:
  ../../libtmux-clojure/src/main/clojure/libtmux/core.clj
[libtmux.data]:
  ../../libtmux-clojure/src/main/clojure/libtmux/data.clj
[libtmux.async]:
  ../../libtmux-clojure/src/main/clojure/libtmux/async.clj
[libtmux.control]:
  ../../libtmux-clojure/src/main/clojure/libtmux/control.clj
