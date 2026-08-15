# select-layout ends the server on 3.3a

## Verdict

Never hand `select-layout` something tmux might not parse. On 3.3a it does not
return an error — it ends the server, and every session on that socket goes with
it, including sessions this program never created.

Both ways in are closed before tmux is asked: the built-in names by an enum, and
a serialized layout by recomputing tmux's own checksum.

## Measurement

A session with two panes, then `select-layout not-a-layout`.

| lane   | answer                                | server afterwards |
| ------ | ------------------------------------- | ----------------- |
| `3.2a` | `can't set layout: not-a-layout`      | alive             |
| `3.3a` | **`server exited unexpectedly`**      | **gone**          |
| `3.4`  | `invalid layout: not-a-layout`        | alive             |
| `3.5`  | `invalid layout: not-a-layout`        | alive             |
| `3.6`  | `invalid layout: not-a-layout`        | alive             |
| `3.7`  | `invalid layout: not-a-layout`        | alive             |
| `3.7a` | `invalid layout: not-a-layout`        | alive             |
| `3.7b` | `invalid layout: not-a-layout`        | alive             |

3.3a alone, and the client cannot tell the difference in advance: the failure
arrives as a dead socket rather than as an error about layouts.

## It is not about names

A malformed *layout string* does the same thing, so guarding the names alone
would have left the hole open:

| lane   | `select-layout 'zzzz,999x999,0,0,9'` | server afterwards |
| ------ | ------------------------------------ | ----------------- |
| `3.2a` | `can't set layout: …`                | alive             |
| `3.3a` | **`server exited unexpectedly`**     | **gone**          |
| `3.7b` | `invalid layout: …`                  | alive             |

Anything `select-layout` cannot parse is fatal there.

## The checksum makes the string checkable

tmux writes a layout as four hex digits, a comma, then the arrangement, where the
digits are a rotate-and-add sum over the rest:

```c
csum = 0;
for (; *layout != '\0'; layout++) {
        csum = (csum >> 1) + ((csum & 1) << 15);
        csum += *layout;
}
```

Recomputing that answers the only question worth asking — did tmux write this? —
without asking tmux. A string that passes is safe to send to 3.3a; one that fails
never goes.

## Layout names by release

| release        | names                                                                   |
| -------------- | ----------------------------------------------------------------------- |
| `3.2a`–`3.4`   | even-horizontal, even-vertical, main-horizontal, main-vertical, tiled   |
| `3.5`–`3.7b`   | the same, plus main-horizontal-mirrored and main-vertical-mirrored      |

The mirrored pair arrived in 3.5, so asking for one on 3.4 would be an unknown
name — which is the fatal case on 3.3a and merely wrong elsewhere. They are
refused before dispatch.

## What cannot be tested here

There is no test that proves the crash, because the guard means no code path can
reach it. The evidence is this note and the probe below; the tests assert only
that the server is still standing and no session was lost after each refusal.

## Commands

```console
$ tmux -S "$sock" -f /dev/null select-layout -t base not-a-layout
```

```console
$ tmux -S "$sock" has-session -t base
```

## Not covered

- Whether the 3.3a crash has other triggers in `select-layout`.
- Whether `next-layout` can reach the same code path.
- Which upstream change fixed it between 3.3a and 3.4.
