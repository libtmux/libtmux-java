package io.github.libtmux.kotlin.query

import io.github.libtmux.Pane_
import io.github.libtmux.kotlin.Pane
import io.github.libtmux.Pane as JavaPane

/*
 * Hand-mapped from the Java field metamodel (io.github.libtmux.Pane_), one property per static
 * method, until field-catalog.tsv's own generator lands and takes this file over. Each wraps a Java
 * *field* object, never the handle type: `Pane.command` (here, on the companion) and an instance
 * `pane.currentCommand` (the captured value) are two different declarations with two different
 * names, so nothing here shadows anything on the handle class itself.
 */

/** The pane id. */
public val Pane.Companion.id: TextField<JavaPane> get() = TextField(Pane_.id())

/** The command tmux reported running in the pane. */
public val Pane.Companion.command: TextField<JavaPane> get() = TextField(Pane_.command())

/** The pane's position in its window. */
public val Pane.Companion.index: NumberField<JavaPane> get() = NumberField(Pane_.index())

/** Whether this was its window's active pane. */
public val Pane.Companion.active: FlagField<JavaPane> get() = FlagField(Pane_.active())

/** The pane's title, which a program running in it can set. */
public val Pane.Companion.title: TextField<JavaPane> get() = TextField(Pane_.title())

/** The pane's working directory, as the text tmux reported. */
public val Pane.Companion.path: TextField<JavaPane> get() = TextField(Pane_.path())

/** The pane's width, in cells. */
public val Pane.Companion.width: NumberField<JavaPane> get() = NumberField(Pane_.width())

/** The pane's height, in cells. */
public val Pane.Companion.height: NumberField<JavaPane> get() = NumberField(Pane_.height())

/** The column of the pane's left edge in its window. */
public val Pane.Companion.left: NumberField<JavaPane> get() = NumberField(Pane_.left())

/** The row of the pane's top edge in its window. */
public val Pane.Companion.top: NumberField<JavaPane> get() = NumberField(Pane_.top())
