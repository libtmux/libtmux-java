# libtmux-clojure-core-async

**Optional core.async integration for libtmux-clojure.**

The Maven coordinate is `io.github.libtmux:libtmux-clojure-core-async`. Add it
only when an application uses `libtmux.core-async`; `libtmux-clojure` itself
does not depend on core.async.

```xml
<dependency>
  <groupId>io.github.libtmux</groupId>
  <artifactId>libtmux-clojure-core-async</artifactId>
  <version>0.0.1-alpha.12</version>
</dependency>
```

> **Alpha.** The API will change without notice. Pin an exact version; it is
> not recommended for production.

Requires JDK 21 or newer and tmux 3.2a through 3.7c.

The adapter owns its event and terminal channels. Its stop function closes its
Java subscription and must be called when the application is finished. The
[Clojure guide](../docs/guide/clojure.md) describes the ownership and loss
boundaries.
