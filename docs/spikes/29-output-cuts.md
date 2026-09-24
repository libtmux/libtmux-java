# Where tmux cuts pushed output

## Verdict

tmux cuts a pane's `%output` by byte count, not by character. A client has to
decode each pane's output as one stream: a UTF-8 character cut between two pushes
belongs to the later one. `PaneOutput.data()` is decoded that way, and
`PaneOutput.bytes()` is each push exactly.

## Source

`control_write_callback` gives each pane with output pending a share of the
8192-byte write buffer: `limit = space / pending_count / 3`, the 3 allowing for
`\ooo` escapes. `control_write_pending` then cuts the pane's queued output at
`limit` bytes, whatever that byte is. `control_append_data` escapes only a byte
below space and the backslash, as three octal digits; every other byte, UTF-8
included, is written raw.

## Measured

tmux 3.7c, one pane, a control client attached with `tmux -C`, 80,000 bytes of
`é` written two ways:

| writer | `%output` lines | lines ending mid-character |
| --- | ---: | ---: |
| `awk`, one `printf` per character | 7,850 | 0 |
| `cat` of a file | 95 | 28 |

A program that writes a character at a time hands tmux one character per read,
and tmux never has to cut one. `cat` writes large blocks, and more than a quarter
of its pushes ended inside a character. Before the fix, each half read back as
`\xHH` text.

## What checks it

`ControlClientTest.aCharacterSplitAcrossOutputLinesArrivesWhole` cuts `café` by
hand in a fake tmux. `ControlModeIntegrationTest.outputCutMidCharacterJoinsBackExactly`
`cat`s the file on every matrix lane and requires the joined text and the joined
bytes to be the file's; decoding each push on its own fails it.
