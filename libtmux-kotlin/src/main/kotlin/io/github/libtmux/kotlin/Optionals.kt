package io.github.libtmux.kotlin

import java.util.OptionalInt
import java.util.OptionalLong

/*
 * OptionalInt/OptionalLong are inert in Kotlin the same way java.util.Optional is, and every
 * OptionalInt/OptionalLong this module's wrapper classes hand back is already unwrapped to a
 * nullable Int/Long (Pane.pid, and every generated accessor). This pair remains for a reused Java
 * type that still returns one directly, such as PaneRun.exitStatus().
 */

/** The value, or null when absent: an exit status, a captured server pid. */
public fun OptionalInt.orNull(): Int? = if (isPresent) asInt else null

/** The value, or null when absent. */
public fun OptionalLong.orNull(): Long? = if (isPresent) asLong else null
