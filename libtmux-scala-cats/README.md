# libtmux-scala-cats

**Cats Effect resources and FS2 observations for `libtmux-scala`.**

This optional module adds scoped blocking execution and loss-aware control
observations. `libtmux-scala` does not depend on Cats Effect or FS2; add this
artifact only when an application uses those integrations. Use the same version
as `libtmux-scala`.

**This project is alpha.** The API is not settled, and a release may change or
remove exported identifiers without a deprecation period. Pin an exact version
rather than a range. Not recommended for production.

The [Scala facade guide](../libtmux-scala/README.md) covers installation,
resource ownership, execution, and streaming contracts.
