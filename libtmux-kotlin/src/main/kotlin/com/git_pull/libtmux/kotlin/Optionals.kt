package com.git_pull.libtmux.kotlin

import com.git_pull.libtmux.Client
import com.git_pull.libtmux.ClientAttachment
import com.git_pull.libtmux.Options
import com.git_pull.libtmux.Pane
import com.git_pull.libtmux.Session
import com.git_pull.libtmux.Window

/*
 * Absence, said the way Kotlin says it.
 *
 * The Java API returns Optional, which is right for Java and inert in Kotlin: `?.`, `?:` and smart
 * casts all work on a nullable and none of them work on an Optional, so a Kotlin caller either wraps
 * every read or gives up the language's own null handling. These are the whole of that wrapping,
 * written once.
 *
 * Only the accessors that can genuinely be absent appear here. This is not a mirror of the API.
 */

/** The session's active window, or null when the session has gone. */
public fun Session.activeWindowOrNull(): Window? = activeWindow().orElse(null)

/** The session's active pane, or null when the session has gone. */
public fun Session.activePaneOrNull(): Pane? = activePane().orElse(null)

/** Whether the pane floats, or null on a tmux older than 3.7, which does not report it. */
public fun Pane.floatingOrNull(): Boolean? = floating().orElse(null)

/** The pane's copy or view mode, or null when it is in none. */
public fun Pane.modeOrNull(): String? = mode().orElse(null)

/** The session this client is attached to, or null when it is attached to none. */
public fun Client.sessionOrNull(): Session? = session().orElse(null)

/** What this client is attached to, or null when the capture saw no attachment. */
public fun Client.attachmentOrNull(): ClientAttachment? = attachment().orElse(null)

/** The option's value, or null when it is not set at this level. */
public fun Options.getOrNull(name: String): String? = get(name).orElse(null)
