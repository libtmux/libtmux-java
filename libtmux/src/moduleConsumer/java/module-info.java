/**
 * A consumer that has a module descriptor of its own, and nothing but this library to read.
 *
 * <p>Not published and not part of the library. It exists to be compiled: a modular consumer resolves
 * every module its dependencies require, {@code static} or not, so a {@code requires transitive
 * static} on an annotation jar that nobody ships makes this fail to compile with {@code module not
 * found} — on a consumer that never mentions annotations at all.
 */
module consumer {
    requires io.github.libtmux;
}
