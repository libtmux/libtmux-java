# What an agent actually does with this surface

## Verdict

The design works on a model that was told nothing about it. Given a task with a
wait in it, a real agent called `tmux_whoami` first, waited rather than polled,
and carried a cursor between two waits without being asked to.

Recorded because tool descriptions are a claim about behaviour, and the only way
to check that claim is to watch an agent that has not been coached.

## Setup

tmux 3.7b on a socket under this port's own root, two windows: `work` running a
shell, and `server` running

```
echo booting; sleep 3; echo "Listening on port 8080"; sleep 600
```

The server was launched at the `destructive` ceiling with `--watch`, over stdio,
by the agent's own MCP client. No project instructions, no prompt about which
tool to use — only the task.

## The task

> Using only the tmux MCP tools: in the pane in the window named 'server', wait
> until it prints that it is listening (stop early if it errors). Then in the
> OTHER pane run `echo hello-from-agent; exit 4` and report its exit status.

## What it did

| order | call | notable |
| ----- | ---- | ------- |
| 1 | `tmux_whoami` | unprompted, and what the instructions ask for first |
| 2 | `tmux_list_windows`, `tmux_list_panes` | issued together |
| 3 | `tmux_wait_for_text` | with a stop pattern, as the description asks |
| 4 | `tmux_wait_for_text` | resumed from the cursor the first returned |
| 5 | `tmux_capture_pane` | once, to confirm |
| 6 | `tmux_run` | reported `exit status: 4` |

Between the two waits it said, unprompted:

> continuing from the wait cursor without rereading old output

Nothing in the task mentioned cursors. That sentence is the tool description and
the returned field doing their job.

**No polling loop anywhere.** The failure this surface exists to prevent —
send a command, then read the pane repeatedly to guess whether it finished — did
not appear, and the exit status came back as a number rather than as something
inferred from the screen.

## The other run, which is also evidence

An earlier attempt asked for the pane "whose window is named `work`". `work` is
the session name; no window has it. The agent listed the panes, noticed, and
stopped:

> Panes: `%0` (`zsh`) and `%1` (`server`), both in session `work`. No window is
> named `work`, so the command was not run; no exit status.

A listing that had shown only ids, or had conflated session and window, would
have let it act on the wrong pane instead.

## What this does not show

One agent, one model, one run each. It is evidence that the surface is
followable, not a measurement of how often it is followed. The token and call
counts that would make it one were not collected.
