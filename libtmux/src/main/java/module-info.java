/**
 * Typed, blocking access to tmux from the JVM.
 *
 * <p>Every package a caller needs is exported and {@code io.github.libtmux.internal} is not: the
 * three classes in it are public only because several packages here share them, which is not a
 * reason for anyone outside to reach them.
 */
module io.github.libtmux {
    // Transitive because both appear in exported signatures, and static because neither is needed
    // at runtime: a consumer reading this API's nullness or its discarded-result checks wants them
    // on its compile path, and a consumer running against it needs nothing. The core still resolves
    // nothing at runtime.
    requires transitive static org.jspecify;
    requires transitive static com.google.errorprone.annotations;

    exports io.github.libtmux;
    exports io.github.libtmux.batch;
    exports io.github.libtmux.control;
    exports io.github.libtmux.format;
    exports io.github.libtmux.query;
    exports io.github.libtmux.snapshot;
    exports io.github.libtmux.transport;
}
