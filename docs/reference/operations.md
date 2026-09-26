# Operations reference

Generated from `@Operation`-annotated methods by the operation-catalog Doclet; do not edit by hand.

| Owner | Method | Kind | Summary |
| --- | --- | --- | --- |
| `io.github.libtmux.Buffers` | `list()` | READ | Captures every buffer the server holds, in tmux's order. |
| `io.github.libtmux.Buffers` | `set(java.lang.String, java.lang.String)` | MUTATION | Puts text in a named buffer, replacing whatever was there. |
| `io.github.libtmux.Buffers` | `show(java.lang.String)` | READ | What a buffer holds, without any line breaks it ends with. |
| `io.github.libtmux.Buffers` | `delete(java.lang.String)` | MUTATION | Removes a buffer by its exact name. |
| `io.github.libtmux.Buffers` | `save(java.lang.String, java.nio.file.Path)` | MUTATION | Writes a buffer's contents to a file. |
| `io.github.libtmux.Buffers` | `load(java.lang.String, java.nio.file.Path)` | MUTATION | Reads a file into a named buffer. |
| `io.github.libtmux.Channel` | `name()` | CAPTURED | The name this channel is known by on its server. |
| `io.github.libtmux.Channel` | `signal()` | MUTATION | Signals the channel, waking one waiter, or being remembered until something waits. |
| `io.github.libtmux.Channel` | `await(java.time.Duration)` | WAIT | Waits for something to signal the channel. |
| `io.github.libtmux.Channel` | `awaitReservingCapacity(java.time.Duration)` | WAIT | Waits while preserving process capacity for a call through this server that signals it. |
| `io.github.libtmux.Channel` | `drain()` | WAIT | Consumes a signal already waiting, so a stale one cannot satisfy a later wait. |
| `io.github.libtmux.Client` | `name()` | CAPTURED | The client's terminal name, which is how tmux addresses it. |
| `io.github.libtmux.Client` | `detach()` | MUTATION | Detaches this client, leaving whatever it was attached to running. |
| `io.github.libtmux.Client` | `detachOthers()` | MUTATION | Detaches every other client, leaving this one attached. |
| `io.github.libtmux.Client` | `switchTo(io.github.libtmux.Session)` | MUTATION | Moves this client to another session. |
| `io.github.libtmux.Client` | `redraw()` | MUTATION | Asks tmux to redraw this client. |
| `io.github.libtmux.Client` | `server()` | CAPTURED | The server this client is connected to. |
| `io.github.libtmux.Client` | `session()` | CAPTURED | The session this client was attached to when captured. |
| `io.github.libtmux.Client` | `attachment()` | CAPTURED | What this client was looking at when captured. |
| `io.github.libtmux.Client` | `fetchAttachment()` | READ | Takes a new capture and returns what this client is looking at now. |
| `io.github.libtmux.Client` | `refresh()` | READ | Takes a new capture and returns this client as it is now. |
| `io.github.libtmux.CommandChain` | `newWindow(java.lang.String)` | MUTATION | Creates a window and makes it the one following steps act on. |
| `io.github.libtmux.CommandChain` | `renameWindow(java.lang.String)` | MUTATION | Renames the current window. |
| `io.github.libtmux.CommandChain` | `splitLeftRight()` | MUTATION | Splits the current pane into a left and a right one. |
| `io.github.libtmux.CommandChain` | `splitTopBottom()` | MUTATION | Splits the current pane into a top and a bottom one. |
| `io.github.libtmux.CommandChain` | `sendLine(java.lang.String)` | MUTATION | Types a line into the current pane and presses Enter, which is how a command gets run. |
| `io.github.libtmux.CommandChain` | `arrange(java.lang.String)` | MUTATION | Arranges the current window. |
| `io.github.libtmux.CommandChain` | `then(java.lang.String[])` | MUTATION | Adds any tmux command, for whatever this class does not name. |
| `io.github.libtmux.CommandChain` | `then(java.util.List<java.lang.String>)` | MUTATION | Adds any tmux command. |
| `io.github.libtmux.CommandChain` | `run()` | MUTATION | Runs the whole chain in one tmux invocation, attributing each step. |
| `io.github.libtmux.Commands` | `list()` | READ | Every command, as tmux prints it. |
| `io.github.libtmux.Environment` | `get(java.lang.String)` | READ | The value set for this name, or empty when it is removed or absent. |
| `io.github.libtmux.Environment` | `isRemoved(java.lang.String)` | READ | Whether new processes are told not to inherit this name. |
| `io.github.libtmux.Environment` | `all()` | READ | Every name set at this scope, in tmux's order. |
| `io.github.libtmux.Environment` | `effective()` | READ | What a process started from this scope is actually handed, both scopes taken together. |
| `io.github.libtmux.Environment` | `removed()` | READ | Every name this scope tells a new process not to inherit. |
| `io.github.libtmux.Environment` | `set(java.lang.String, java.lang.String)` | MUTATION | Sets a name to a literal value. |
| `io.github.libtmux.Environment` | `setExpanded(java.lang.String, java.lang.String)` | MUTATION | Sets a name to what a tmux format expands to now. |
| `io.github.libtmux.Environment` | `unset(java.lang.String)` | MUTATION | Removes the name from this scope, so it is neither set nor marked. |
| `io.github.libtmux.Environment` | `remove(java.lang.String)` | MUTATION | Tells processes started after this not to inherit the name. |
| `io.github.libtmux.Hooks` | `set(java.lang.String, java.lang.String)` | MUTATION | Binds a tmux command to an event at this scope, replacing anything already bound to it. |
| `io.github.libtmux.Hooks` | `set(java.lang.String, java.util.List<java.lang.String>)` | MUTATION | As {@link #set(String, String)}, with the command as words rather than a line tmux parses. |
| `io.github.libtmux.Hooks` | `append(java.lang.String, java.util.List<java.lang.String>)` | MUTATION | As {@link #append(String, String)}, with the command as words. |
| `io.github.libtmux.Hooks` | `append(java.lang.String, java.lang.String)` | MUTATION | Binds another command to an event, after whatever is already bound to it. |
| `io.github.libtmux.Hooks` | `unset(java.lang.String)` | MUTATION | Removes everything bound to an event at this scope. |
| `io.github.libtmux.Hooks` | `run(java.lang.String)` | MUTATION | Runs what is bound to an event now, without waiting for the event. |
| `io.github.libtmux.Hooks` | `all()` | READ | Every hook at this scope, keyed by event, each with its commands in the order tmux runs them. |
| `io.github.libtmux.Keys` | `in(java.lang.String)` | CAPTURED | The same bindings, in one named table. |
| `io.github.libtmux.Keys` | `bind(java.lang.String, java.util.List<java.lang.String>)` | MUTATION | Binds a key to a tmux command, given as its words. |
| `io.github.libtmux.Keys` | `unbind(java.lang.String)` | MUTATION | Removes a key's binding. |
| `io.github.libtmux.Keys` | `list()` | READ | The bindings as tmux lists them, one per line. |
| `io.github.libtmux.MessageLog` | `lines()` | READ | The log lines. |
| `io.github.libtmux.Options` | `get(java.lang.String)` | READ | The value in effect at this scope, inherited from a parent scope when this one does not set it. |
| `io.github.libtmux.Options` | `get(io.github.libtmux.OptionKey<T>)` | READ | As {@link #get(String)}, read as the key's type. |
| `io.github.libtmux.Options` | `set(io.github.libtmux.OptionKey<T>, T)` | MUTATION | As {@link #set(String, String)}, written the way tmux reads the key's type. |
| `io.github.libtmux.Options` | `all()` | READ | Every option set at this scope, in tmux's order. |
| `io.github.libtmux.Options` | `effective()` | READ | Every option tmux lists at this scope, including inherited built-in values. |
| `io.github.libtmux.Options` | `set(java.lang.String, java.lang.String)` | MUTATION | Sets one option at this scope. |
| `io.github.libtmux.Options` | `setIfAbsent(java.lang.String, java.lang.String)` | MUTATION | Sets one option only if this scope does not already set it. |
| `io.github.libtmux.Options` | `append(java.lang.String, java.lang.String)` | MUTATION | Adds to the end of a string option, rather than replacing it. |
| `io.github.libtmux.Options` | `setExpanded(java.lang.String, java.lang.String)` | MUTATION | Sets one option to what a tmux format comes to, rather than to the format itself. |
| `io.github.libtmux.Options` | `unset(java.lang.String)` | MUTATION | Removes one option at this scope, so it falls back to whatever it inherits. |
| `io.github.libtmux.Pane` | `info()` | CAPTURED | The fields this capture stored. |
| `io.github.libtmux.Pane` | `id()` | CAPTURED | The pane's stable id. |
| `io.github.libtmux.Pane` | `index()` | CAPTURED | The pane's position, which shifts as neighbours come and go. |
| `io.github.libtmux.Pane` | `active()` | CAPTURED | Whether this was its window's active pane when captured. |
| `io.github.libtmux.Pane` | `currentCommand()` | CAPTURED | The command tmux reported running here. |
| `io.github.libtmux.Pane` | `floating()` | CAPTURED | Whether this pane floats, or empty when the running tmux cannot say. |
| `io.github.libtmux.Pane` | `size()` | CAPTURED | How large the pane was when captured, in terminal cells. |
| `io.github.libtmux.Pane` | `position()` | CAPTURED | Where the pane's top-left corner sat in its window when captured, in terminal cells. |
| `io.github.libtmux.Pane` | `title()` | CAPTURED | The pane title, which a program running inside it can change. |
| `io.github.libtmux.Pane` | `currentPath()` | CAPTURED | The working directory tmux reported for the pane. |
| `io.github.libtmux.Pane` | `currentPathText()` | CAPTURED | The working directory tmux reported for the pane, exactly as tmux reported it. |
| `io.github.libtmux.Pane` | `pid()` | CAPTURED | The process id of the program running in the pane, captured at the time of the read this handle came from. |
| `io.github.libtmux.Pane` | `dead()` | READ | Whether this pane's process has exited, read fresh rather than from the capture. |
| `io.github.libtmux.Pane` | `resize(io.github.libtmux.Direction, int)` | MUTATION | Grows this pane by a number of cells in one direction. |
| `io.github.libtmux.Pane` | `edges()` | CAPTURED | Which sides of its window the pane touches. |
| `io.github.libtmux.Pane` | `copyMode()` | MUTATION | Puts this pane into copy mode, where its scrollback can be navigated. |
| `io.github.libtmux.Pane` | `mode()` | READ | Which mode this pane is in at this moment, or empty when it is in none. |
| `io.github.libtmux.Pane` | `exitMode()` | MUTATION | Leaves whichever mode this pane is in. |
| `io.github.libtmux.Pane` | `select()` | MUTATION | Makes this the active pane of its window. |
| `io.github.libtmux.Pane` | `retitle(java.lang.String)` | MUTATION | Retitles this pane and returns its replacement capture. |
| `io.github.libtmux.Pane` | `resizeTo(io.github.libtmux.Dimensions)` | MUTATION | Resizes this pane. |
| `io.github.libtmux.Pane` | `server()` | CAPTURED | The server this pane lives on. |
| `io.github.libtmux.Pane` | `batch()` | CAPTURED | Collects commands fenced to the server incarnation that produced this pane. |
| `io.github.libtmux.Pane` | `window()` | CAPTURED | The window link this pane was reached through. |
| `io.github.libtmux.Pane` | `hooks()` | CAPTURED | This pane's own hooks. |
| `io.github.libtmux.Pane` | `options()` | CAPTURED | This pane's own options. |
| `io.github.libtmux.Pane` | `capture()` | READ | This pane's visible content, one element per line. |
| `io.github.libtmux.Pane` | `noteTyped(java.lang.String)` | MUTATION | Notes text that reached this pane without going through this handle, so a wait discounts its echo the way it discounts text sent through {@link #sendLiteral}. |
| `io.github.libtmux.Pane` | `awaitText(java.lang.String, java.time.Duration)` | WAIT | Waits until this pane's text contains something, and says why the wait ended. |
| `io.github.libtmux.Pane` | `awaitText(java.lang.String, java.time.Duration, java.time.Duration)` | WAIT | As {@link #awaitText(String, Duration)}, looking every {@code every} rather than every 50 ms. |
| `io.github.libtmux.Pane` | `run(java.lang.String, java.time.Duration)` | WAIT | Runs a shell command in this pane to its end, and answers with its exit status and output. |
| `io.github.libtmux.Pane` | `await(java.util.function.Predicate<io.github.libtmux.Pane>, java.time.Duration)` | WAIT | Waits until a condition holds for this pane as tmux reports it, and says why the wait ended. |
| `io.github.libtmux.Pane` | `await(java.util.function.Predicate<io.github.libtmux.Pane>, java.time.Duration, java.time.Duration)` | WAIT | As {@link #await(Predicate, Duration)}, looking every {@code every} rather than every 50 ms. |
| `io.github.libtmux.Pane` | `capture(java.util.function.Consumer<io.github.libtmux.CaptureSpec.Builder>)` | READ | Reads part of this pane, described by a lambda. |
| `io.github.libtmux.Pane` | `capture(io.github.libtmux.CaptureSpec)` | READ | Reads part of this pane according to a spec, which may be reused across panes. |
| `io.github.libtmux.Pane` | `send(java.lang.String)` | MUTATION | Sends keys to this pane without pressing Enter. |
| `io.github.libtmux.Pane` | `sendKeys(java.util.List<java.lang.String>)` | MUTATION | Sends an ordered group of key names to this pane, as tmux resolves them. |
| `io.github.libtmux.Pane` | `sendKeys(java.util.List<java.lang.String>, java.lang.Runnable)` | MUTATION | As {@link #sendKeys(List)}, after a caller validates the prepared input destination. |
| `io.github.libtmux.Pane` | `sendLiteral(java.util.List<java.lang.String>)` | MUTATION | Sends an ordered group of strings to this pane as the characters they spell. |
| `io.github.libtmux.Pane` | `sendLiteral(java.util.List<java.lang.String>, java.lang.Runnable)` | MUTATION | As {@link #sendLiteral(List)}, after a caller validates the prepared input destination. |
| `io.github.libtmux.Pane` | `sendLine(java.lang.String)` | MUTATION | Sends a line to this pane and presses Enter, which is how a command gets run. |
| `io.github.libtmux.Pane` | `breakOut()` | MUTATION | Moves this pane into a window of its own, leaving tmux to name it. |
| `io.github.libtmux.Pane` | `expand(java.lang.String)` | READ | Expands a tmux format in this pane's context, and answers with what it came to. |
| `io.github.libtmux.Pane` | `variables(java.util.List<java.lang.String>)` | READ | Reads validated tmux variables in this pane's format context. |
| `io.github.libtmux.Pane` | `respawn()` | MUTATION | Kills whatever runs here and starts the pane's default command again. |
| `io.github.libtmux.Pane` | `respawnIn(java.nio.file.Path)` | MUTATION | Restarts the configured pane process in a caller-supplied literal directory, resolved against this process's working directory when relative. |
| `io.github.libtmux.Pane` | `respawn(java.lang.String[])` | MUTATION | Kills whatever runs here and starts the given command instead. |
| `io.github.libtmux.Pane` | `pipeTo(java.lang.String)` | MUTATION | Sends everything this pane prints to a shell command, until {@link #stopPiping}. |
| `io.github.libtmux.Pane` | `stopPiping()` | MUTATION | Stops sending this pane's output anywhere. |
| `io.github.libtmux.Pane` | `breakOut(java.lang.String)` | MUTATION | Moves this pane into a window of its own with the given name. |
| `io.github.libtmux.Pane` | `split()` | MUTATION | Splits this pane in half, putting the new one below it. |
| `io.github.libtmux.Pane` | `split(java.util.function.Consumer<io.github.libtmux.SplitSpec.Builder>)` | MUTATION | Splits this pane as described. |
| `io.github.libtmux.Pane` | `split(io.github.libtmux.SplitSpec)` | MUTATION | Splits this pane according to a spec, which may be reused across panes. |
| `io.github.libtmux.Pane` | `pasteBuffer(java.lang.String)` | MUTATION | Pastes a named buffer into this pane, as though it had been typed. |
| `io.github.libtmux.Pane` | `paste(java.lang.String)` | MUTATION | Pastes text into this pane as one block, leaving nothing behind on the server. |
| `io.github.libtmux.Pane` | `paste(java.lang.String, java.lang.Runnable)` | MUTATION | As {@link #paste(String)}, after a caller rechecks state once its private buffer is ready. |
| `io.github.libtmux.Pane` | `clearHistory()` | MUTATION | Discards this pane's scrollback. |
| `io.github.libtmux.Pane` | `swapWith(io.github.libtmux.Pane)` | MUTATION | Swaps this pane's position with another's. |
| `io.github.libtmux.Pane` | `joinTo(io.github.libtmux.Window)` | MUTATION | Moves this pane into another window, splitting it. |
| `io.github.libtmux.Pane` | `kill()` | MUTATION | Closes this pane. |
| `io.github.libtmux.Pane` | `refresh()` | READ | Takes a new capture and returns this pane as it is now. |
| `io.github.libtmux.Prompt` | `history()` | READ | What has been typed at the prompt, oldest first. |
| `io.github.libtmux.Prompt` | `clear()` | MUTATION | Forgets what has been typed at the prompt. |
| `io.github.libtmux.Server` | `newSession(java.lang.String)` | MUTATION | Creates a detached session and returns that exact session. |
| `io.github.libtmux.Server` | `newSession(java.util.function.Consumer<io.github.libtmux.SessionSpec.Builder>)` | MUTATION | Creates a session as described. |
| `io.github.libtmux.Server` | `newSession(io.github.libtmux.SessionSpec)` | MUTATION | Creates a session according to a spec, which may be reused across servers. |
| `io.github.libtmux.Server` | `hasSession(java.lang.String)` | READ | Whether a session with this name exists. |
| `io.github.libtmux.Server` | `killSession(java.lang.String)` | MUTATION | Ends the session with this name, and everything in it. |
| `io.github.libtmux.Server` | `isAlive()` | READ | Whether the server is running and answering. |
| `io.github.libtmux.Server` | `isAlive(java.time.Duration)` | READ | Whether the server is running and answering, within a deadline of the caller's choosing. |
| `io.github.libtmux.Server` | `requireAlive()` | READ | Requires a running tmux daemon that answers the liveness probe. |
| `io.github.libtmux.Server` | `killServer()` | MUTATION | Ends the tmux server and every session on it. |
| `io.github.libtmux.Server` | `killServer(java.time.Duration)` | MUTATION | Ends the tmux server and every session on it, within a deadline of the caller's choosing. |
| `io.github.libtmux.Server` | `batch()` | CAPTURED | Collects several commands to run in one tmux invocation. |
| `io.github.libtmux.Server` | `chain()` | CAPTURED | Starts a chain of commands where each one acts on what the last one made. |
| `io.github.libtmux.Server` | `expand(java.lang.String)` | READ | Expands a tmux format against the server, and answers with what it came to. |
| `io.github.libtmux.Server` | `shell()` | CAPTURED | Shell commands run by tmux, and tmux commands chosen by a shell exit status. |
| `io.github.libtmux.Server` | `commands()` | CAPTURED | The commands this tmux knows. |
| `io.github.libtmux.Server` | `lock()` | MUTATION | Locks every client attached to this server. |
| `io.github.libtmux.Server` | `messageLog()` | CAPTURED | The server's message log. |
| `io.github.libtmux.Server` | `prompt()` | CAPTURED | The command prompt's history. |
| `io.github.libtmux.Server` | `channel(java.lang.String)` | CAPTURED | One of this server's wait-for channels, which is where a signal is sent and waited for. |
| `io.github.libtmux.Server` | `variables(java.util.List<java.lang.String>)` | READ | Reads only validated tmux variable names, never caller-authored format syntax. |
| `io.github.libtmux.Server` | `paneFields(java.util.List<java.lang.String>)` | READ | Chosen fields for every pane on the server, from one listing. |
| `io.github.libtmux.Server` | `keys()` | CAPTURED | The server's key bindings: {@code prefix} when binding, every table when listing. |
| `io.github.libtmux.Server` | `buffers()` | CAPTURED | The server's paste buffers, which every session shares. |
| `io.github.libtmux.Server` | `sourceFile(java.nio.file.Path)` | MUTATION | Runs a file of tmux commands, as a configuration file would be run. |
| `io.github.libtmux.Server` | `options()` | CAPTURED | The server-wide options, the ones tmux keeps once per server. |
| `io.github.libtmux.Server` | `globalOptions()` | CAPTURED | The global session options every session inherits unless it sets its own. |
| `io.github.libtmux.Server` | `environment()` | CAPTURED | The server's environment, which every session inherits and every new process is given. |
| `io.github.libtmux.Server` | `hooks()` | CAPTURED | The global hooks every session inherits. |
| `io.github.libtmux.Server` | `version()` | READ | Which tmux this server is running. |
| `io.github.libtmux.Server` | `identity()` | CAPTURED | Which server this is. |
| `io.github.libtmux.Server` | `open(io.github.libtmux.ServerConfig)` | LIFECYCLE | A server over a transport it owns and closes. |
| `io.github.libtmux.Server` | `using(io.github.libtmux.ServerConfig, io.github.libtmux.transport.TmuxTransport)` | LIFECYCLE | A server over a transport the caller owns. |
| `io.github.libtmux.Server` | `within(java.time.Duration)` | LIFECYCLE | This server, with every command bounded by a deadline of the caller's choosing. |
| `io.github.libtmux.Server` | `builder()` | LIFECYCLE | A builder holding the documented defaults. |
| `io.github.libtmux.Server` | `config()` | CAPTURED | How this server was configured. |
| `io.github.libtmux.Server` | `cmd(java.lang.String[])` | MUTATION | Runs one tmux command against this server. |
| `io.github.libtmux.Server` | `cmd(java.util.List<java.lang.String>)` | MUTATION | Runs one tmux command against this server. |
| `io.github.libtmux.Server` | `cmd(java.util.List<java.lang.String>, java.time.Duration)` | MUTATION | Runs one tmux command against this server, overriding the configured deadline. |
| `io.github.libtmux.Server` | `control(io.github.libtmux.Session)` | LIFECYCLE | Attaches a control client to this session's server, and only to the process this capture named. |
| `io.github.libtmux.Server` | `control(io.github.libtmux.Session, java.time.Duration)` | LIFECYCLE | As {@link #control(Session)}, with the caller's deadline for the attach and the identity read. |
| `io.github.libtmux.Server` | `snapshot()` | READ | Captures the whole hierarchy in at most four listings, retrying once if the server is replaced. |
| `io.github.libtmux.Server` | `sessions(io.github.libtmux.query.FilterExpr<io.github.libtmux.Session>)` | READ | The sessions this expression matches, captured now. |
| `io.github.libtmux.Server` | `sessions()` | READ | Every session, captured now. |
| `io.github.libtmux.Server` | `windows()` | READ | Captures every winlink, preserving each session and index placement. |
| `io.github.libtmux.Server` | `windows(io.github.libtmux.query.FilterExpr<io.github.libtmux.Window>)` | READ | The winlinks this expression matches, captured now. |
| `io.github.libtmux.Server` | `panes()` | READ | Captures every pane on the server. |
| `io.github.libtmux.Server` | `panes(io.github.libtmux.query.FilterExpr<io.github.libtmux.Pane>)` | READ | The panes this expression matches, captured now. |
| `io.github.libtmux.Server` | `session(io.github.libtmux.query.FilterExpr<io.github.libtmux.Session>)` | READ | The one session this expression matches, captured now, or empty when none does. |
| `io.github.libtmux.Server` | `window(io.github.libtmux.query.FilterExpr<io.github.libtmux.Window>)` | READ | The one window link this expression matches, captured now, or empty when none does. |
| `io.github.libtmux.Server` | `pane(io.github.libtmux.query.FilterExpr<io.github.libtmux.Pane>)` | READ | The one pane this expression matches, captured now, or empty when none does. |
| `io.github.libtmux.Server` | `admissionBound()` | CAPTURED | How many tmux commands this server's transport runs at once, from {@link ServerConfig#maxConcurrentCommands()} for a server {@link #open}ed here. |
| `io.github.libtmux.Server` | `session(java.lang.String)` | READ | The session with this name, captured now. |
| `io.github.libtmux.Server` | `session(io.github.libtmux.SessionId)` | READ | The session with this id, captured now. |
| `io.github.libtmux.Server` | `pane(io.github.libtmux.PaneId)` | READ | The pane with this id, captured now. |
| `io.github.libtmux.Server` | `window(io.github.libtmux.snapshot.WindowContext)` | READ | The winlink at this exact position, captured now. |
| `io.github.libtmux.Server` | `windows(io.github.libtmux.WindowId)` | READ | Every winlink of the window with this id, captured now. |
| `io.github.libtmux.Server` | `clients()` | READ | Captures every attached client. |
| `io.github.libtmux.Server` | `attachedSessions()` | READ | Captures every session a client is attached to. |
| `io.github.libtmux.Server` | `run(java.util.List<java.lang.String>)` | MUTATION | Runs a command that is expected to work, and raises when it did not. |
| `io.github.libtmux.Server` | `toBuilder()` | CAPTURED | A builder holding every configuration and ownership choice this server made. |
| `io.github.libtmux.Server` | `close()` | LIFECYCLE | Releases an owned transport. |
| `io.github.libtmux.Session` | `info()` | CAPTURED | The fields this capture stored. |
| `io.github.libtmux.Session` | `id()` | CAPTURED | The session's stable id. |
| `io.github.libtmux.Session` | `name()` | CAPTURED | The session name, which a user may change at any time. |
| `io.github.libtmux.Session` | `attached()` | CAPTURED | Whether a client was attached when this was captured. |
| `io.github.libtmux.Session` | `server()` | CAPTURED | The server this session lives on. |
| `io.github.libtmux.Session` | `activeWindow()` | CAPTURED | The window tmux had active in this session. |
| `io.github.libtmux.Session` | `activePane()` | CAPTURED | The active pane of the active window. |
| `io.github.libtmux.Session` | `selectWindow(io.github.libtmux.Window)` | MUTATION | Makes a window of this session the active one. |
| `io.github.libtmux.Session` | `nextWindow()` | MUTATION | Moves to the next window in this session, wrapping at the end. |
| `io.github.libtmux.Session` | `previousWindow()` | MUTATION | Moves to the previous window in this session, wrapping at the start. |
| `io.github.libtmux.Session` | `lastWindow()` | MUTATION | Returns to the window that was active before this one. |
| `io.github.libtmux.Session` | `detachClients()` | MUTATION | Detaches every client attached to this session, leaving the session running. |
| `io.github.libtmux.Session` | `options()` | CAPTURED | This session's own options. |
| `io.github.libtmux.Session` | `environment()` | CAPTURED | This session's own environment, which a process started in it is given on top of the server's. |
| `io.github.libtmux.Session` | `hooks()` | CAPTURED | This session's own hooks. |
| `io.github.libtmux.Session` | `setHistoryLimit(int)` | MUTATION | Sets the scrollback retained by panes created in this session. |
| `io.github.libtmux.Session` | `windows()` | CAPTURED | This session's windows, in tmux's order. |
| `io.github.libtmux.Session` | `newWindow(java.lang.String)` | MUTATION | Creates a window in this session and returns that exact window. |
| `io.github.libtmux.Session` | `newWindow(java.util.function.Consumer<io.github.libtmux.WindowSpec.Builder>)` | MUTATION | Creates a window in this session as described. |
| `io.github.libtmux.Session` | `newWindow(io.github.libtmux.WindowSpec)` | MUTATION | Creates a window in this session according to a spec, which may be reused across sessions. |
| `io.github.libtmux.Session` | `expand(java.lang.String)` | READ | Expands a tmux format in this session's context, and answers with what it came to. |
| `io.github.libtmux.Session` | `rename(java.lang.String)` | MUTATION | Renames this session and returns its replacement capture. |
| `io.github.libtmux.Session` | `kill()` | MUTATION | Ends this session. |
| `io.github.libtmux.Session` | `refresh()` | READ | Takes a new capture and returns this session as it is now. |
| `io.github.libtmux.Shell` | `run(java.lang.String)` | MUTATION | Runs a shell command for its effect. |
| `io.github.libtmux.Shell` | `capturing(java.lang.String)` | MUTATION | Runs a shell command and answers with what it printed. |
| `io.github.libtmux.Shell` | `choose(java.lang.String, java.lang.String)` | MUTATION | Runs one tmux command when a shell command succeeds. |
| `io.github.libtmux.Shell` | `choose(java.lang.String, java.lang.String, java.lang.String)` | MUTATION | Runs one tmux command or another, according to whether a shell command succeeds. |
| `io.github.libtmux.Window` | `info()` | CAPTURED | The fields this capture stored. |
| `io.github.libtmux.Window` | `id()` | CAPTURED | The underlying window, shared by every link to it. |
| `io.github.libtmux.Window` | `index()` | CAPTURED | Where this link sits in its session. |
| `io.github.libtmux.Window` | `context()` | CAPTURED | The winlink this handle addresses. |
| `io.github.libtmux.Window` | `name()` | CAPTURED | The window name. |
| `io.github.libtmux.Window` | `active()` | CAPTURED | Whether this was its session's active window when captured. |
| `io.github.libtmux.Window` | `linked()` | CAPTURED | Whether the underlying window is linked into more than one session. |
| `io.github.libtmux.Window` | `size()` | CAPTURED | How large the window was when captured, in terminal cells. |
| `io.github.libtmux.Window` | `layout()` | CAPTURED | tmux's own layout for this window, in whichever form this server writes it. |
| `io.github.libtmux.Window` | `activePane()` | CAPTURED | The pane tmux had active here. |
| `io.github.libtmux.Window` | `select()` | MUTATION | Makes this the active window of its session. |
| `io.github.libtmux.Window` | `server()` | CAPTURED | The server this window lives on. |
| `io.github.libtmux.Window` | `session()` | CAPTURED | The session this link belongs to. |
| `io.github.libtmux.Window` | `hooks()` | CAPTURED | This window's own hooks, which every link to it shares. |
| `io.github.libtmux.Window` | `options()` | CAPTURED | This window's own options, which every link to it shares. |
| `io.github.libtmux.Window` | `panes()` | CAPTURED | This link's panes, in tmux's order. |
| `io.github.libtmux.Window` | `split()` | MUTATION | Splits this window's active pane in half, putting the new one below it. |
| `io.github.libtmux.Window` | `split(java.util.function.Consumer<io.github.libtmux.SplitSpec.Builder>)` | MUTATION | Splits this window's active pane as described. |
| `io.github.libtmux.Window` | `split(io.github.libtmux.SplitSpec)` | MUTATION | Splits this window's active pane according to a spec, which may be reused across windows. |
| `io.github.libtmux.Window` | `expand(java.lang.String)` | READ | Expands a tmux format in this window's context, and answers with what it came to. |
| `io.github.libtmux.Window` | `rename(java.lang.String)` | MUTATION | Renames this window and returns its replacement capture. |
| `io.github.libtmux.Window` | `linkTo(io.github.libtmux.Session)` | MUTATION | Links this window into another session, so one window sits in both. |
| `io.github.libtmux.Window` | `unlink()` | MUTATION | Removes this link, leaving the window wherever else it is linked. |
| `io.github.libtmux.Window` | `moveTo(io.github.libtmux.Session)` | MUTATION | Moves this window into another session. |
| `io.github.libtmux.Window` | `moveTo(io.github.libtmux.Session, int)` | MUTATION | Moves this window to an exact index in another session. |
| `io.github.libtmux.Window` | `resizeTo(io.github.libtmux.Dimensions)` | MUTATION | Resizes this window in terminal cells. |
| `io.github.libtmux.Window` | `synchronizePanes()` | MUTATION | Copies input to one pane into every pane in this window, until {@link #stopSynchronizingPanes}. |
| `io.github.libtmux.Window` | `stopSynchronizingPanes()` | MUTATION | Stops copying input between this window's panes. |
| `io.github.libtmux.Window` | `rotate()` | MUTATION | Rotates the panes within this window. |
| `io.github.libtmux.Window` | `selectLayout(io.github.libtmux.Layout)` | MUTATION | Rearranges this window's panes into one of tmux's built-in layouts. |
| `io.github.libtmux.Window` | `nextLayout()` | MUTATION | Moves to the next built-in layout, as tmux's own binding does. |
| `io.github.libtmux.Window` | `previousLayout()` | MUTATION | Moves to the previous built-in layout, as tmux's own binding does. |
| `io.github.libtmux.Window` | `applyLayout(io.github.libtmux.WindowLayout)` | MUTATION | Restores an exact arrangement previously read from {@link #layout()}. |
| `io.github.libtmux.Window` | `applyLayout(java.lang.String)` | MUTATION | As {@link #applyLayout(WindowLayout)}, for a layout held as text — one saved to a file, say. |
| `io.github.libtmux.Window` | `respawn()` | MUTATION | Kills what is running in this window and starts it again. |
| `io.github.libtmux.Window` | `displayPopup(java.lang.String)` | MUTATION | Shows a popup over this window, running a command in it. |
| `io.github.libtmux.Window` | `kill()` | MUTATION | Closes this window. |
| `io.github.libtmux.Window` | `refresh()` | READ | Takes a new capture and returns this winlink as it is now. |
| `io.github.libtmux.batch.Batch` | `add(java.lang.String[])` | MUTATION | Adds one tmux command, its arguments already separate elements. |
| `io.github.libtmux.batch.Batch` | `add(java.util.List<java.lang.String>)` | MUTATION | Adds one tmux command. |
| `io.github.libtmux.batch.Batch` | `size()` | CAPTURED | How many operations have been collected. |
| `io.github.libtmux.batch.Batch` | `length()` | CAPTURED | How many bytes the collected operations come to as the one command tmux parses. |
| `io.github.libtmux.batch.Batch` | `run()` | MUTATION | Runs every collected operation in one tmux invocation. |
| `io.github.libtmux.control.ControlClient` | `attachUnfenced(io.github.libtmux.ServerConfig, io.github.libtmux.SessionId)` | LIFECYCLE | Attaches a control client to whatever server now answers this endpoint. |
| `io.github.libtmux.control.ControlClient` | `attachUnfenced(io.github.libtmux.ServerConfig, io.github.libtmux.SessionId, java.time.Duration)` | LIFECYCLE | As {@link #attachUnfenced(ServerConfig, SessionId)}, waiting up to the supplied deadline for the client's opening reply. |
| `io.github.libtmux.control.ControlClient` | `attach(io.github.libtmux.ServerConfig, io.github.libtmux.SessionId, long, java.lang.String, java.time.Duration)` | LIFECYCLE | Attaches, then leaves unless the live server is still the process that was captured. |
| `io.github.libtmux.control.ControlClient` | `attach(io.github.libtmux.transport.ControlCarrier, io.github.libtmux.ServerConfig, io.github.libtmux.SessionId, long, java.util.OptionalLong, java.lang.String, java.time.Duration)` | LIFECYCLE | As {@link #attach(ServerConfig, SessionId, long, String, Duration)}, started by the carrier that also starts this realm's commands, and checking the start time a capture recorded too. |
| `io.github.libtmux.control.ControlClient` | `send(java.lang.String[])` | MUTATION | Runs one command and waits for its reply. |
| `io.github.libtmux.control.ControlClient` | `send(java.util.List<java.lang.String>)` | MUTATION | Runs one command and waits for its reply. |
| `io.github.libtmux.control.ControlClient` | `send(java.util.List<java.lang.String>, java.time.Duration)` | MUTATION | Runs one command and waits for its reply. |
| `io.github.libtmux.control.ControlClient` | `isAlive()` | CAPTURED | Whether the client is still running. |
| `io.github.libtmux.control.ControlClient` | `subscribeOutput(int)` | STREAM | Subscribes to terminal output tmux pushes. |
| `io.github.libtmux.control.ControlClient` | `subscribeEvents(int)` | STREAM | Subscribes to state changes tmux volunteers. |
| `io.github.libtmux.control.ControlClient` | `watch(java.lang.String, java.lang.String, java.lang.String)` | MUTATION | Asks tmux to report a format whenever its value changes. |
| `io.github.libtmux.control.ControlClient` | `unwatch(java.lang.String)` | MUTATION | Stops a watch. |
| `io.github.libtmux.control.ControlClient` | `close()` | LIFECYCLE | Ends the client, rejecting queued requests and resolving picked requests as uncertain. |
| `io.github.libtmux.control.ControlClient` | `standardError()` | CAPTURED | The error text captured from this control process, at most 4096 bytes. |
| `io.github.libtmux.control.ControlClient` | `standardErrorTruncated()` | CAPTURED | Whether {@link #standardError()} stopped before the process finished writing it. |
| `io.github.libtmux.control.EventSubscription` | `droppedCount()` | CAPTURED | Returns the exact number of events discarded because this subscription's buffer was full. |
| `io.github.libtmux.control.EventSubscription` | `cause()` | CAPTURED | Why this subscription ended, when the control client ended it. |
| `io.github.libtmux.control.EventSubscription` | `next()` | WAIT | Waits until the next step arrives or this subscription closes. |
| `io.github.libtmux.control.EventSubscription` | `next(java.time.Duration)` | WAIT | Waits up to a deadline for the next step. |
| `io.github.libtmux.control.EventSubscription` | `poll()` | CAPTURED | Returns the next step if one is waiting, without blocking. |
| `io.github.libtmux.control.EventSubscription` | `onReady(java.lang.Runnable)` | CAPTURED | Arms a one-shot wakeup for when this subscription has something to read, or has ended. |
| `io.github.libtmux.control.EventSubscription` | `clearReady()` | CAPTURED | Disarms the readiness callback, if one is armed. |
| `io.github.libtmux.control.EventSubscription` | `stream()` | STREAM | The steps still to come, in order, each read by the thread consuming the stream. |
| `io.github.libtmux.control.EventSubscription` | `publisher()` | STREAM | A {@link Flow.Publisher} view of this subscription's steps, delivered as demand allows. |
| `io.github.libtmux.control.EventSubscription` | `publisher(java.util.concurrent.Executor)` | STREAM | A {@link Flow.Publisher} view of this subscription's steps, delivered as demand allows, its reads run on {@code executor} rather than a virtual thread. |
| `io.github.libtmux.control.EventSubscription` | `isClosed()` | CAPTURED | Whether this subscription has reached its terminal state. |
| `io.github.libtmux.control.EventSubscription` | `close()` | LIFECYCLE | Discards buffered events, removes this subscriber, and wakes every waiting reader. |
| `io.github.libtmux.snapshot.ServerMirror` | `open(io.github.libtmux.Session)` | LIFECYCLE | Mirrors the server {@code anchor} belongs to, listening through a control client attached to {@code anchor}. |
| `io.github.libtmux.snapshot.ServerMirror` | `open(io.github.libtmux.Session, java.time.Duration)` | LIFECYCLE | As {@link #open(Session)}, also rebuilding when nothing has been announced for {@code  refreshEvery}, so a change tmux does not announce to this client is seen within that long. |
| `io.github.libtmux.snapshot.ServerMirror` | `current()` | CAPTURED | The latest published view. |
| `io.github.libtmux.snapshot.ServerMirror` | `awaitNewer(long, java.time.Duration)` | WAIT | Waits for a view newer than {@code epoch}. |
| `io.github.libtmux.snapshot.ServerMirror` | `onNewer(long, java.lang.Runnable)` | CAPTURED | Arms a one-shot wakeup for when a view newer than {@code epoch} is published or the mirror ends. |
| `io.github.libtmux.snapshot.ServerMirror` | `clearNewer()` | CAPTURED | Disarms the callback {@link #onNewer} armed, if one is armed. |
| `io.github.libtmux.snapshot.ServerMirror` | `isEnded()` | CAPTURED | Whether this mirror has stopped publishing, because it was closed or its anchor has gone. |
| `io.github.libtmux.snapshot.ServerMirror` | `cause()` | CAPTURED | Why this mirror ended, when something other than {@link #close()} ended it. |
| `io.github.libtmux.snapshot.ServerMirror` | `close()` | LIFECYCLE | Stops listening and detaches the control client. |
