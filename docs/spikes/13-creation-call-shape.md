# How a creation call carries its options

## Verdict

A builder-built spec, applied by three overloads: no argument, a lambda
configuring the builder, or a prebuilt spec. Choices are named as verbs on the
builder rather than as setters taking enum constants or wrapper values.

A class rather than a record, for the reason `ServerConfig` already gives: a
record's canonical constructor is public API, and `split-window` gained six flags
in 3.7 alone. The candidates below were written as records and would have had to
change anyway.

```java
Pane logs = pane.split();
Pane side = pane.split(s -> s.toRight().percent(30));
Pane app  = pane.split(s -> s.running("htop").in(root));

SplitSpec sidebar = SplitSpec.builder().toRight().percent(25).build();
left.split(sidebar);
right.split(sidebar);
```

`split-window` takes seventeen options in the Python sibling, `new-window` and
`new-session` most of the same ones. Whatever shape is chosen here is the shape
all three wear.

## Candidates

Each was written as compiling Java and given the same five tasks.

**A — spec record and builder.** What `java.net.http.HttpRequest`, OkHttp and the
AWS SDK settled on. Verbose at the call site: every choice is a setter taking a
value that must itself be named.

**B — chain with a terminal verb.** `pane.splitting().toRight().open()`. Reads
well; `splitting()` performs nothing, and a spec cannot outlive the pane it
started from.

**C — sealed option values as varargs.** `pane.split(toRight(), size(...))`.
Shortest to type; eight public types to A's three, contradictions resolved by
argument order, and not a shape any widely used Java library offers.

**D — A, with B's directional verbs and C's size verbs on the builder.** The
wordiness measured in A came from the setter-per-field style, not from the spec
object.

## Call-site size

Characters, whitespace removed. Same five tasks, same shared vocabulary.

| task                             | A   | B   | C   | D   |
| -------------------------------- | --- | --- | --- | --- |
| plain split                      | 13  | 13  | 13  | 13  |
| to the right, 30%                | 59  | 57  | 57  | **39** |
| command, directory, environment  | 106 | 86  | **83** | 86 |
| empty pane                       | 38  | 32  | 26  | **25** |
| one description, two panes       | 132 | 115 | **103** | 108 |

C's two wins cost five extra public types. B's 115 on the last row is two copies
of the same chain: a chain is bound to the pane it started from, so there is
nothing to hand to the second pane.

## What none of them prevent

Every candidate was given a set of things a user should not get away with, and
each row is a pass only if `javac` refuses it.

| written                                   | A | B | C | D |
| ----------------------------------------- | - | - | - | - |
| one value holding both empty and a command | rejected | rejected | rejected | rejected |
| a size where a direction goes              | rejected | rejected | rejected | rejected |
| empty and a command in one expression      | compiles | compiles | compiles | compiles |
| described, then never performed            | compiles | compiles | compiles | compiles |
| `percent(400)`                             | compiles | compiles | compiles | compiles |

Sealing `Start` means no *value* can carry both an emptiness and a command, which
is what tmux itself refuses. It does not stop someone writing both in one
expression — every candidate resolves that last-wins. The difference is only that
`s.empty().running("htop")` reads as changing one's mind, while
`split(empty(), running("htop"))` reads as asking for both.

"Described, then never performed" compiles everywhere, but it means different
things. A stray `SplitSpec.builder().build()` is an unused value and looks like
one. A stray `pane.splitting().toRight()` is a statement that looks exactly like
a split and is not one. That asymmetry, not the character counts, is what ruled
out B: it is the only candidate whose mistake is invisible at the call site.
Error Prone's `@CheckReturnValue` would catch it, but a shape that needs a
checker to be safe loses to one that does not.

Range checks stay where they were: a runtime `IllegalArgumentException` from the
compact constructor. Nothing in Java 21 makes `percent(400)` a compile error at
an acceptable price.

## Not covered

- Whether the same builder should serve `new-window` and `new-session` or each
  should have its own; only the split was written out.
- `@CheckReturnValue` on the spec types, which would need
  `error_prone_annotations` on the core compile path.
