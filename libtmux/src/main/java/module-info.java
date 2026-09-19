/**
 * Typed, blocking access to tmux from the JVM.
 *
 * <p>Every package a caller needs is exported and {@code io.github.libtmux.internal} is not: the
 * three classes in it are public only because several packages here share them, which is not a
 * reason for anyone outside to reach them.
 */
module io.github.libtmux {
    // Static because neither is needed at runtime, and deliberately not transitive. Transitive made
    // every consumer with a descriptor of its own fail to compile with "module not found:
    // org.jspecify" — a modular consumer resolves what its dependencies require whether or not the
    // requirement is static, and these are compileOnly, so nothing ships them. A consumer that wants
    // to read this API's nullness puts JSpecify on its own path, which it needs there anyway for its
    // own code. The core still resolves nothing at runtime.
    requires static org.jspecify;
    requires static com.google.errorprone.annotations;

    exports io.github.libtmux;
    exports io.github.libtmux.batch;
    exports io.github.libtmux.control;
    exports io.github.libtmux.format;
    exports io.github.libtmux.query;
    exports io.github.libtmux.snapshot;
    exports io.github.libtmux.transport;
}
