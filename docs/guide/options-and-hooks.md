# Options and hooks

Every snippet here is executed by `DocumentationSnippetsTest`.

tmux keeps options and hooks at four scopes, and the same name can exist at more
than one. A scope is chosen when the view is taken, so a caller cannot read one
and write another:

```java
// Given: Server server, Session session, Window window, Pane pane
server.globalOptions().set("base-index", "1");
session.options().set("status-left", "mine");
window.options().set("automatic-rename", "off");
pane.options().set("remain-on-exit", "on");
```

## Narrow and wide

`all()` lists what this scope sets. `effective()` lists what tmux will act on,
including what the scope inherits:

```java
// Given: Session session
Options options = session.options();

options.all().isEmpty();                          // → true
options.effective().isEmpty();                    // → false
```

A fresh session sets nothing of its own, and acts on plenty.

`get(name)` answers with what tmux will act on, which is the wide question.

A wide listing marks an inherited name with a trailing star — `status-left*`.
That star never reaches you: the name you look up is the name you get back, and
`all()` already answers which scope set it.

## As their types

`OptionKey` names an option and how its value reads, so a number comes back as
an `Integer` and a flag as a `Boolean` rather than as `"on"`:

```java
// Given: Session session
session.options().set(OptionKey.HISTORY_LIMIT, 50_000);

session.options().get(OptionKey.HISTORY_LIMIT).orElseThrow();   // → 50000
```

A few options every supported release types the same way are constants. For any
other, declare one — `OptionKey.flag("visual-bell")`, `OptionKey.number(...)`,
`OptionKey.text(...)` — since tmux adds options between releases and a
catalogue here would be wrong on the ones it did not track. A key with the wrong
type fails naming the option rather than guessing.

## Writing without replacing

```java
// Given: Session session
Options options = session.options();

options.set("status-left", "one");
options.append("status-left", "-two");

options.get("status-left").orElseThrow();         // → one-two
options.setIfAbsent("status-left", "three");      // → false
```

`setExpanded` stores what a format comes to rather than the format itself, so
`in #{session_name}` is written as the name it expanded to.

`setIfAbsent` answers with whether the value was taken. tmux calls the
already-set case an error; declining to overwrite is the request, not a failure.

`setExpanded` stores what the format came to, once, at the call. The option does
not stay live.

## Hooks are arrays

Every hook is a list of commands tmux runs in order, so a hook set once is a list
of one rather than a special case:

```java
// Given: Session session
Hooks hooks = session.hooks();

hooks.set("after-new-window", "display-message one");
hooks.append("after-new-window", "display-message two");

hooks.all().get("after-new-window");              // → [display-message one, display-message two]
```

`set` replaces the whole array; `append` adds to it. `run(event)` runs what is
bound without waiting for the event.

## The scope a hook lives at

A hook belongs to one scope, and setting it anywhere else is **accepted and then
silently discarded** — no error, on any supported release:

```java
// Given: Window window
window.hooks().set("pane-focus-in", "display-message belongs-here");   // a window hook
window.hooks().set("alert-bell", "display-message does-not");          // a session hook

window.hooks().all().containsKey("pane-focus-in");   // → true
window.hooks().all().containsKey("alert-bell");      // → false
```

The second one is gone, quietly: tmux keeps it at the session, not the window.

So a hook that never fires is worth checking against `all()` before it is worth
debugging.
