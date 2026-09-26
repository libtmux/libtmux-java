package io.github.libtmux.catalog;

/**
 * What shape a facade needs to give one operation of the core API.
 *
 * <p>A Kotlin or Scala wrapper, or a generated reference page, reads this off {@link Operation}
 * rather than guessing from a method's name or return type.
 */
public enum Kind {

    /**
     * No tmux I/O: answers from the handle's own capture, or collects a command to send later. Field
     * accessors, navigation within one capture, and building a batch or chain.
     */
    CAPTURED,

    /** Contacts tmux and changes nothing: listings, captures, {@code show-*}, expansion, liveness, lookups. */
    READ,

    /** Changes tmux state: sending keys, renaming, killing, splitting, setting options, pasting, resizing. */
    MUTATION,

    /** Blocks until something else happens; the waiting itself changes nothing. */
    WAIT,

    /** Returns a subscription, stream, or publisher of events. */
    STREAM,

    /** Opens, attaches, derives, or closes a resource. */
    LIFECYCLE
}
