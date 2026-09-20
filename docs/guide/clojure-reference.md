# Clojure captured-value reference

`libtmux.data/from-capture` creates every value in this reference. `snapshot!`
and the `*!` listing functions acquire one captured graph; `data/windows`,
`data/panes`, `data/active-window` and `data/active-pane` traverse it without
I/O. Scalar access is ordinary Clojure keyword lookup and is also local.

Every owning map has its documented key, even when the value is `nil` because
tmux did not report an optional value. A missing relationship means the parent
or target was absent from an otherwise successful capture. Failed acquisition
throws; it is never an empty graph.

`data/data` removes every `:tmux/ref`. The ref is the only effect authority;
editing `:session/id`, `:window/id` or `:pane/id` changes a map, not tmux.
`data/same-entity?` compares `:tmux/identity`. `data/same-link?` also compares
the window placement in `:tmux/link`; it does not establish a daemon generation
or detect an unseen identical unlink-and-relink replacement.

<!-- clojure-captured-fields:
[:client/active-pane
 :client/active-window
 :client/name
 :client/session
 :pane/active?
 :pane/current-command
 :pane/current-path
 :pane/edges
 :pane/floating?
 :pane/height
 :pane/id
 :pane/index
 :pane/left
 :pane/pid
 :pane/title
 :pane/top
 :pane/width
 :session/attached?
 :session/id
 :session/name
 :session/windows
 :tmux/captured-at
 :tmux/clients
 :tmux/identity
 :tmux/id
 :tmux/kind
 :tmux/link
 :tmux/panes
 :tmux/pid
 :tmux/realm
 :tmux/ref
 :tmux/server
 :tmux/sessions
 :tmux/version
 :tmux/windows
 :window/active?
 :window/height
 :window/id
 :window/index
 :window/layout
 :window/linked?
 :window/name
 :window/panes
 :window/width]
-->

## Shared keys

- `:tmux/ref` is the Java handle. `data/data` removes it; effects and
  `FilterExpr` need it, while identity does not.
- `:tmux/identity` is always-present endpoint, server-PID, kind and physical-ID
  data. `same-entity?` compares it.
- `:tmux/link` exists only on windows and panes. It holds session, window and
  index occurrence context; `same-link?` compares it.
- `:tmux/realm` and `:tmux/server` are always-present identity text. Neither
  has a Java filter field.
- `:tmux/pid` is an integer or `nil`; the key stays present when tmux does not
  report a daemon or pane PID. It has no Java filter field.
- `:tmux/kind` is always-present `:server`, `:session`, `:window`, `:pane` or
  `:client` identity data. It has no Java filter field.
- `:tmux/id` is always-present physical identity text. Editing it cannot
  retarget an effect, and it has no Java filter field.
- `:tmux/captured-at` is a root-only `Instant`; `data/data` writes ISO-8601
  text. `:tmux/version` is root-only text or `nil` when unavailable. Neither
  has a Java filter field.

## Root and sessions

- `:tmux/sessions`, `:tmux/windows`, `:tmux/panes` and `:tmux/clients` are
  root-only captured vectors. They have no root Java relation.
- `:session/id` and `:session/name` are always-present text. `Session_/id` and
  `Session_/name` support Java filtering.
- `:session/attached?` is an always-present Boolean. `Session_/attached`
  supports Java filtering.
- `:session/windows` is an always-present local vector relation. `data/windows`
  traverses it and `Session_/windows` is the canonical Java relation.

## Windows and panes

- `:window/id` is always-present underlying-window text across links.
  `Window_/id` supports Java filtering.
- `:window/index` is the always-present index within `:tmux/link`.
  `Window_/index` supports Java filtering.
- `:window/name` is always-present text. `Window_/name` supports Java
  filtering.
- `:window/active?` is an always-present Boolean for this link's session.
  `Window_/active` supports Java filtering.
- `:window/linked?` is an always-present Boolean stating that the underlying
  window has another link. `Window_/linked` supports Java filtering.
- `:window/width` and `:window/height` are always-present captured cell sizes;
  `:window/layout` is always-present layout text. They have no Java fields.
- `:window/panes` is an always-present local vector relation. `data/panes`
  traverses it and `Window_/panes` is the canonical Java relation.
- `:pane/id` is always-present physical-pane text; `Pane_/id` supports Java
  filtering. `:pane/index` is an always-present window index; `Pane_/index`
  supports Java filtering.
- `:pane/active?` is an always-present Boolean for the captured window.
  `Pane_/active` supports Java filtering.
- `:pane/current-command` and `:pane/current-path` are always-present tmux
  text. `Pane_/command` and `Pane_/path` support Java filtering.
- `:pane/floating?` is Boolean or `nil` when tmux does not report it. The key
  stays present and it has no Java field.
- `:pane/width` and `:pane/height` are always-present cell sizes; `Pane_/width`
  and `Pane_/height` support Java filtering.
- `:pane/left` and `:pane/top` are always-present captured positions;
  `Pane_/left` and `Pane_/top` support Java filtering.
- `:pane/title` is always-present text; `Pane_/title` supports Java filtering.
- `:pane/pid` is an integer or `nil` when tmux does not report it. The key stays
  present and it has no Java field.
- `:pane/edges` is an always-present map with unqualified Boolean edge keys.
  It has no Java field.

## Clients

- `:client/name` is always-present tmux client text; `Client_/name` supports
  Java filtering.
- `:client/session` is a session map or `nil` when no session is attached. The
  key stays present and `Client_/session` is the canonical Java relation.
- `:client/active-window` and `:client/active-pane` are a captured map or
  `nil` when no attached session supplies them. Their keys stay present and
  they have no Java fields.

Java expressions always evaluate the preserved Java ref through
`data/predicate` or `data/matching`; they never evaluate edited or detached
maps. `Pane_` has no registered pane-to-window relation, so use captured
traversal for that direction instead of inventing a canonical Java relation.
