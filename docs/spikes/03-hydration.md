# Immutable hierarchy hydration

## Verdict

Capture one server-wide listing per entity kind — sessions, windows, panes,
clients — and carry the server's own fields on the session rows. Four commands,
whatever the topology's size.

Reading each entity from the command that owns it keeps ordering and membership
tmux's decision rather than the client's. Folding server identity into the
session format removes the separate identity query without giving that up.

Relations are keyed on the window context, not the window id, because a window
linked into two sessions is one window and two positions.

## Fixed oracle

Every contender implements the same capture interface, reads the same format
tokens, and assembles its rows through the same indexer. Only the listings each
one issues differ, so a plan cannot win by reading less. The oracle requires,
on every released lane:

- session, window-context, and pane identity and order matching tmux's own
  `list-sessions`, `list-windows -a`, and `list-panes -a`;
- a window linked into two sessions at different indexes appearing once per
  session, with one intrinsic id, two unequal contexts that stay distinct in a
  hash set, and both contexts reaching its panes;
- the version-gated `pane_floating_flag` reported as absent rather than false
  before tmux 3.7, and present after;
- active window and pane selections surviving capture;
- zero commands issued by any traversal, in fifty sequential rounds and
  thirty-two concurrent ones, including lookups that miss;
- a refresh performing the same bounded capture again, returning a new graph and
  leaving the original's contents and hash untouched; and
- every exposed list and map rejecting mutation while retaining encounter order.

Two properties keep the purity gate honest. Traversal is asserted against a
counter that must have moved during capture, and a separate case proves the
counter moves when a command is issued — a frozen counter is only evidence if it
can advance.

## Version matrix

All eight released lanes are built from pinned commits in an upstream clone of
`https://github.com/tmux/tmux.git`, and each binary must print exactly the
release it claims before any lane is accepted.

| lane   | commit    | lane   | commit    |
| ------ | --------- | ------ | --------- |
| `3.2a` | `3b929f3` | `3.6`  | `0dac7fe` |
| `3.3a` | `0b355ae` | `3.7`  | `81f88f8` |
| `3.4`  | `9ae69c3` | `3.7a` | `0e418b6` |
| `3.5`  | `ac44566` | `3.7b` | `e802909` |

A commit is the provenance rather than an archive checksum: the tags are
annotated but unsigned, so a published hash would attest to the same clone this
already reads from. Every lane runs every case; 96 cases per contender, 32 for
the synthesis, none skipped.

## Topology

One fixture shape, chosen for what capture plans get wrong: session `alpha`
with `win-a` (two panes) and `win-b`, session `beta` with `solo`, `win-a` linked
into `beta` at index 9, `win-b` selected, and the second pane of `win-a`
selected. Two sessions, four window contexts, six panes.

## Contender results

| plan            | commands | shape                                                        |
| --------------- | -------- | ------------------------------------------------------------ |
| scoped listings | 9        | `3 + sessions + window contexts`; grows with the topology    |
| one pane rowset | 3        | constant; sessions and windows derived from the pane rows    |
| per-entity `-a` | 5        | constant; one listing per entity kind plus an identity query |
| synthesis       | 4        | constant; identity folded onto the session rows              |

All four capture the same graph on every lane, with identity, ordering, linked
contexts, active selections, and gated-token absence matching tmux.

### Direct scoped listings

One `list-windows` per session and one `list-panes` per window context. Rows
arrive already inside the scope that produced them, so a linked window is read
once per session with no de-duplication to get wrong, and panes are targeted as
`session:index` because a linked window id alone does not say which session's
copy is meant.

It pays a process launch per window. Nine commands for four window contexts is
already the largest count here, and the cost is unbounded in the size of the
server rather than in what the caller asked for.

### One hierarchical pane rowset

Every pane in the server in a single `list-panes -a` carrying session and window
fields, with sessions and windows recovered by taking each the first time a row
mentions it. Three commands, and it holds on every lane.

Two costs are measurable rather than theoretical. Repeating each session's fields
once per pane makes the same topology 698 bytes against 476 for per-entity
listings, a 1.47x increase that scales with panes per session. And its ordering
is derived: it is correct only because `list-panes -a` happens to visit sessions
and windows in the same order their own listings do. That was tested directly,
including sessions whose creation order and name order disagree — all three
listings agreed on every lane — but tmux documents no such guarantee, so the
plan depends on a coincidence rather than a contract.

### Per-entity server-wide listings

Four listings plus an identity query. Ordering and membership come from the
command that owns each entity, so nothing is derived and nothing needs
de-duplicating.

## Ranking and synthesis

The protocol ranks lexicographically.

| criterion        | scoped listings                     | pane rowset                                          | per-entity listings  |
| ---------------- | ----------------------------------- | ---------------------------------------------------- | -------------------- |
| correctness      | pass                                | pass                                                 | pass                 |
| downstream API   | pass                                | pass                                                 | pass                 |
| maintenance      | loss: command count tracks topology | loss: order derived from an undocumented equivalence | win: nothing derived |
| performance      | not ranked after maintenance loss   | not ranked; observed 3 commands, 1.47x bytes         | observed 5 commands  |
| build complexity | not ranked                          | not ranked                                           | accepted             |

The three plans tie on correctness and expose the same API, so maintenance
decides. The pane rowset is the cheapest in commands and would win a performance
comparison, but performance ranks below maintenance and its correctness rests on
an ordering tmux does not promise.

Synthesis rebuilds the per-entity plan and grafts the rowset's useful half: the
observation that server-level fields resolve on any row. Reading `pid`,
`socket_path`, `start_time`, and `version` from the session rows removes the
identity query, taking the constant count from five commands to four without
deriving anything. The standalone identity query remains for a server with no
sessions — a state a live server does not reach, kept so the capture cannot
report a server it never asked about.

## Version-gated tokens

tmux expands an unknown token to the empty string, exactly as it expands a known
token whose value is empty. A probe across the matrix confirms the wire cannot
separate them:

| token                | 3.2a  | 3.7b  |
| -------------------- | ----- | ----- |
| an invented token    | `[]`  | `[]`  |
| `pane_search_string` | `[]`  | `[]`  |
| `pane_floating_flag` | `[]`  | `[0]` |
| `window_raw_flags`   | `[*]` | `[*]` |

So absence is version knowledge, not wire knowledge. For flag and numeric tokens
the two are still separable in practice, because a supported one is never empty,
and `pane_floating_flag` is the case that proves it: empty through 3.6, `0` from
3.7. The captured value is therefore optional, and reported as absent rather
than false on older servers — collapsing it would claim every old server has no
floating panes, which is a different statement from not knowing.

`window_raw_flags` answers on 3.2a, so it needs no gate. That was measured
rather than inferred; it had been assumed to be a later addition.

## Not covered

- The differential compares the captured graph against tmux's own listings, not
  against Python libtmux. Comparing to raw tmux is the stronger oracle for
  identity and ordering, but the Python comparison the plan also calls for has
  not run, so no claim of Python parity is made here.
- No attached client exists in the fixture, so `list-clients` is exercised as an
  empty listing on every lane. Client capture is unproven beyond that.
- The topology is one shape. Deeper nesting, many sessions, and dead panes are
  untested.

## Result record

| gate                   | oracle                                                               | scoped listings | pane rowset | per-entity  | synthesis   | verdict                                                     | artifact digests                                                                                                                                                                                                                                                                                           |
| ---------------------- | -------------------------------------------------------------------- | --------------- | ----------- | ----------- | ----------- | ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| version matrix         | eight lanes built from pinned commits, each printing its own version | pass            | pass        | pass        | pass        | all lanes ran                                               | `sha256:b8a9b6de6de209dec3ac7101dcfa97ba583d2fa6b18919657c0b81dc192d59c3`, `sha256:35a963ad6b5615256032cb1661ccf366e52f22faf505c611a307abd4a146a393`                                                                                                                                                       |
| topology differential  | identity and order against tmux's own listings, all lanes            | pass, 96 cases  | pass, 96    | pass, 96    | pass, 32    | pass                                                        | `sha256:d27000219bf74b3c57da3c45b062d520ca241a9b607e80571152451c9caee6d2`, `sha256:6305b6fb44a3c3f202d09576b2edcf6643f651dba21ce6ec892dcec8e82c8945`, `sha256:68e41873a02dccf02ef7d4d8f415a851dc7630e9407a8ee7a281a74d013924ea`, `sha256:08381e6af8ea888e26506bd52bce290cb1ec43ceba9a4a6fbcf86ce6242bc7c8` |
| linked-window identity | one id, two unequal contexts, both reaching the panes                | pass            | pass        | pass        | pass        | context keying required                                     | `sha256:d27000219bf74b3c57da3c45b062d520ca241a9b607e80571152451c9caee6d2`                                                                                                                                                                                                                                  |
| gated token            | absent before 3.7, present after, never collapsed to false           | pass            | pass        | pass        | pass        | optional value required                                     | `sha256:08381e6af8ea888e26506bd52bce290cb1ec43ceba9a4a6fbcf86ce6242bc7c8`                                                                                                                                                                                                                                  |
| snapshot purity        | zero commands across sequential, concurrent, and missing lookups     | pass            | pass        | pass        | pass        | pass                                                        | `sha256:d27000219bf74b3c57da3c45b062d520ca241a9b607e80571152451c9caee6d2`                                                                                                                                                                                                                                  |
| collection contracts   | mutation rejected, encounter order retained                          | pass            | pass        | pass        | pass        | pass                                                        | `sha256:68e41873a02dccf02ef7d4d8f415a851dc7630e9407a8ee7a281a74d013924ea`                                                                                                                                                                                                                                  |
| command count          | commands per capture on the fixture topology                         | 9, grows        | 3, constant | 5, constant | 4, constant | synthesis is constant and cheapest of the non-derived plans | `sha256:c9e1171bd5998109d952ecccafc7e694ba539b01be96053507ed56c221e483dc`, `sha256:4363b624f9f52a9b9f4123fb318662f6497b22ebfeae10f5d0180bfec965a8b9`, `sha256:7a756cf4ae3331c3bf2d86081870aba915c08ec213dea36e6650712f66952326`, `sha256:b231ee8a01dad314ccba020691462d7e230fe85d51f6c515e86e162eb86d4630` |
| process cleanup        | locked owned-process verifier over 2154 ledger rows                  | pass            | pass        | pass        | pass        | pass                                                        | `sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`                                                                                                                                                                                                                                  |

The cleanup verifier produces no output on success, so its row carries the
digest of the empty file.

## Commands

The matrix is built once, from pinned commits in an upstream clone:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/hydration/oracle/build-tmux-matrix.sh" \
        --manifest "$root/hydration/oracle/tmux-matrix.tsv" \
        --prefix "$root/tools/tmux" \
        --source "$HOME/study/c/tmux" \
        > "$root/artifacts/tmux-matrix.log" 2>&1'
```

Each contender runs every lane in its own scope:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        hydration-scoped-listings \
        reuse \
        product \
        "$root/hydration" \
        -PspikeRoot="$root" \
        -PtmuxMatrix="$root/tools/tmux" \
        --rerun-tasks \
        :scoped-listings:test \
        > "$root/artifacts/hydration-scoped-listings.log" 2>&1'
```

The synthesis runs the same oracle:

```console
$ sh -eu -c \
    'root="$(git config --local --get codex.libtmux-java-spike-root)"; \
    "$root/artifacts/run-gradle.sh" \
        hydration-synthesis \
        reuse \
        product \
        "$root/hydration" \
        -PspikeRoot="$root" \
        -PtmuxMatrix="$root/tools/tmux" \
        --rerun-tasks \
        :synthesis:test \
        > "$root/artifacts/hydration-synthesis.log" 2>&1'
```

## Evidence digests

Artifact labels are relative to the disposable root. No nonce, PID, hostname,
username, or local absolute path is durable.

| artifact                                         | SHA-256                                                            |
| ------------------------------------------------ | ------------------------------------------------------------------ |
| `artifacts/tmux-matrix.log`                      | `b8a9b6de6de209dec3ac7101dcfa97ba583d2fa6b18919657c0b81dc192d59c3` |
| `hydration/oracle/tmux-matrix.tsv`               | `35a963ad6b5615256032cb1661ccf366e52f22faf505c611a307abd4a146a393` |
| `artifacts/hydration-scoped-listings.log`        | `d27000219bf74b3c57da3c45b062d520ca241a9b607e80571152451c9caee6d2` |
| `artifacts/hydration-pane-rowset.log`            | `6305b6fb44a3c3f202d09576b2edcf6643f651dba21ce6ec892dcec8e82c8945` |
| `artifacts/hydration-hybrid.log`                 | `68e41873a02dccf02ef7d4d8f415a851dc7630e9407a8ee7a281a74d013924ea` |
| `artifacts/hydration-synthesis.log`              | `08381e6af8ea888e26506bd52bce290cb1ec43ceba9a4a6fbcf86ce6242bc7c8` |
| `artifacts/hydration-counts/scoped-listings.tsv` | `c9e1171bd5998109d952ecccafc7e694ba539b01be96053507ed56c221e483dc` |
| `artifacts/hydration-counts/pane-rowset.tsv`     | `4363b624f9f52a9b9f4123fb318662f6497b22ebfeae10f5d0180bfec965a8b9` |
| `artifacts/hydration-counts/hybrid.tsv`          | `7a756cf4ae3331c3bf2d86081870aba915c08ec213dea36e6650712f66952326` |
| `artifacts/hydration-counts/synthesis.tsv`       | `b231ee8a01dad314ccba020691462d7e230fe85d51f6c515e86e162eb86d4630` |
| `artifacts/hydration-cleanup.log`                | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `artifacts/task-5-evidence.sha256`               | `36325b45ee68f45b13416714e0c1feb35d16c683a5eb474d20c9652f19e30787` |

## Real-tmux JUnit lifecycle

A `@TmuxServer` annotation plus a `ParameterResolver` gives each test its own
server, resolved per parameter so a test may hold several. The extension keeps
them in its own store rather than in test state, so the framework destroys them
on the way out however the test ended.

The teardown guarantee is asserted from the case that matters. Proving it from
passing tests would leave the failing path unproven, so a deliberately failing
case is run through the JUnit Platform launcher and its socket is required to be
gone afterwards. That case carries a tag ordinary discovery excludes, because a
fixture that exists to fail is not a result; the launcher applies no tag filter
and still selects it.

Teardown failures are collected rather than thrown one at a time, so one server
refusing to die cannot hide the others.

| artifact                                | SHA-256                                                            |
| --------------------------------------- | ------------------------------------------------------------------ |
| `artifacts/junit-extension-results.tsv` | `a0f4f225d9a4f203023d9461786a3c73cb8cfc6254f57f2e6c21b96b1650c5df` |
