# Options and hooks

Every snippet here is executed by `ExamplesTest`.

tmux keeps options and hooks at four scopes, and the same name can exist at more
than one. A scope is chosen when the view is taken, so a caller cannot read one
and write another:

```java
server.globalOptions().set("base-index", "1");
session.options().set("status-left", "mine");
window.options().set("automatic-rename", "off");
pane.options().set("remain-on-exit", "on");
```

## Narrow and wide

`all()` lists what this scope sets. `effective()` lists what tmux will act on,
including what the scope inherits:

```java
Options options = session.options();

assertTrue(options.all().isEmpty());              // a fresh session sets nothing
assertFalse(options.effective().isEmpty());       // but it acts on plenty
```

`get(name)` answers with what tmux will act on, which is the wide question.

A wide listing marks an inherited name with a trailing star — `status-left*`.
That star never reaches you: the name you look up is the name you get back, and
`all()` already answers which scope set it.

## Writing without replacing

```java
options.set("status-left", "one");
options.append("status-left", "-two");            // one-two

options.setIfAbsent("status-left", "three");      // false: already set
options.setExpanded("status-left", "in #{session_name}");   // stores "in work"
```

`setIfAbsent` answers with whether the value was taken. tmux calls the
already-set case an error; declining to overwrite is the request, not a failure.

`setExpanded` stores what the format came to, once, at the call. The option does
not stay live.

## Hooks are arrays

Every hook is a list of commands tmux runs in order, so a hook set once is a list
of one rather than a special case:

```java
Hooks hooks = session.hooks();
hooks.set("after-new-window", "display-message one");
hooks.append("after-new-window", "display-message two");

assertEquals(List.of("display-message one", "display-message two"),
        hooks.all().get("after-new-window"));
```

`set` replaces the whole array; `append` adds to it. `run(event)` runs what is
bound without waiting for the event.

## The scope a hook lives at

A hook belongs to one scope, and setting it anywhere else is **accepted and then
silently discarded** — no error, on any supported release:

```java
window.hooks().set("pane-focus-in", "display-message belongs-here");   // a window hook
window.hooks().set("alert-bell", "display-message does-not");          // a session hook

assertTrue(window.hooks().all().containsKey("pane-focus-in"));
assertFalse(window.hooks().all().containsKey("alert-bell"));           // gone, quietly
```

So a hook that never fires is worth checking against `all()` before it is worth
debugging.
