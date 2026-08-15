# CPython subprocess compatibility study

CPython `v3.14.6` is the deliberately selected reference implementation and
was the exact interpreter used by the isolated compatibility-vector probe. The
project declares Python `>=3.10,<4.0`; it does not pin `v3.14.6`. The Java
transport must match libtmux's observable normalization, not every CPython
implementation detail.

## Source contract

libtmux invokes `Popen` with separate stdout and stderr pipes, text mode,
UTF-8, and `backslashreplace`, then calls `communicate`. It splits each decoded
channel on newline, removes all trailing empty stdout elements, removes every
empty stderr element, and applies the historical `has-session` rewrite only at
the Python wrapper layer. See the pinned [libtmux command wrapper](https://github.com/tmux-python/libtmux/blob/c4a980b/src/libtmux/common.py#L330-L377).

CPython supplies the lower-level mechanics:

- Text pipes wrap the binary descriptors with the requested encoding and error
  handler ([`Popen` stream construction](https://github.com/python/cpython/blob/v3.14.6/Lib/subprocess.py#L1003-L1037)).
- POSIX `communicate` registers stdout and stderr together, drains ready pipes
  until EOF, joins the bytes, and then decodes each channel
  ([selector drain](https://github.com/python/cpython/blob/v3.14.6/Lib/subprocess.py#L2093-L2202)).
- Text translation decodes with the configured error handler and normalizes
  CRLF and CR to LF ([newline translation](https://github.com/python/cpython/blob/v3.14.6/Lib/subprocess.py#L1098-L1100)).
- `communicate` waits for termination, while `run` kills and reaps after a
  timeout before re-raising ([communication contract](https://github.com/python/cpython/blob/v3.14.6/Lib/subprocess.py#L1177-L1245), [timeout cleanup](https://github.com/python/cpython/blob/v3.14.6/Lib/subprocess.py#L555-L580)).
- POSIX argv conversion rejects embedded NUL while converting byte strings to C
  strings ([argv conversion](https://github.com/python/cpython/blob/v3.14.6/Modules/_posixsubprocess.c#L193-L255)).

## Compatibility vectors

The following vectors were executed through the actual pinned
`libtmux.common.tmux_cmd` wrapper using a disposable helper executable. The
notation is the observable `CommandResult` line-list shape required from Java.

| Behavior                | Child output or argv                            | Python result                                            | Required Java result                                                                                        |
| ----------------------- | ----------------------------------------------- | -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Invalid UTF-8           | stdout bytes `61 ff`                            | stdout `['a\\xff']`, stderr `[]`, exit 0                 | Decode UTF-8 and emit the lowercase `\\xff` escape for the invalid byte without losing adjacent valid text. |
| No final newline        | stdout bytes for `alpha`                        | stdout `['alpha']`, stderr `[]`, exit 0                  | Preserve the final unterminated line.                                                                       |
| Repeated final newlines | stdout bytes for `alpha\n\n\n`                  | stdout `['alpha']`, stderr `[]`, exit 0                  | Remove every trailing empty stdout element, not only one.                                                   |
| Empty stderr            | stdout `ok\n`; stderr empty                     | stdout `['ok']`, stderr `[]`, exit 0                     | Drain stderr concurrently and return an empty immutable list.                                               |
| Literal semicolon       | one argv element `left;right` printed to stdout | stdout `['left;right']`, stderr `[]`, exit 0             | Keep the semicolon inside one argv element; never introduce a shell or a tmux sequence separator.           |
| Embedded NUL            | one argv element containing `a\0b`              | `ValueError: embedded null byte` before a process result | Reject the request before launch with a programmer-input exception.                                         |

The vector for invalid UTF-8 is byte-oriented. Java must implement CPython's
`backslashreplace` output for every malformed byte sequence, not rely on the
decoder's replacement character. CRLF and lone CR inputs are additional direct
vectors from CPython's newline translator.

## Java transport gates

- Pass argv as a list to `ProcessBuilder`; do not concatenate a shell command.
- Reserve both pipe drainers before launch and drain stdout and stderr
  concurrently through EOF before completing a result.
- Use one monotonic deadline for process wait, destruction, stream closure, and
  drain joins. Timeout and interruption after launch have unknown outcome.
- On timeout, attempt bounded graceful destruction, then forced destruction,
  join both drains, and preserve interrupt status and suppressed cleanup errors.
- Normalize decoded stdout and stderr only after both byte streams are complete.
- Keep raw channels truthful. Apply `has-session` compatibility at the
  high-level method rather than moving stderr into stdout in the transport.
