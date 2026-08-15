# benchmarks

**Measures what each carrier costs. Not published.**

`ExecutionMode` changes how a command travels, never what it answers. This
measures the difference in price, and regenerates
[`docs/benchmarks/modes.md`](../docs/benchmarks/modes.md) from a real run.

```console
$ ./gradlew modeBenchmark -PlibtmuxTmux=/path/to/tmux
```

The table is **never hand-edited**. It shows identical answers next to different
prices, which is the only honest way to present a performance switch.

Its own module, and excluded from `check`: a benchmark starts a tmux server per
case and takes seconds. Keeping it inside a published artifact's tests made that
a matter of remembering a tag rather than a matter of where the code lives.

## Next

- [Execution modes](../docs/guide/execution-modes.md) · [the measured table](../docs/benchmarks/modes.md)
