package io.github.libtmux.kotlin.query

import io.github.libtmux.Client_
import io.github.libtmux.kotlin.Client
import io.github.libtmux.Client as JavaClient
import io.github.libtmux.Session as JavaSession

/** Hand-mapped from `io.github.libtmux.Client_`; see `PaneFields.kt`'s header for why. */

/** The client's terminal name, which is how tmux addresses it. */
public val Client.Companion.name: TextField<JavaClient> get() = TextField(Client_.name())

/** The session this client was attached to, if any. */
public val Client.Companion.session: ToOneField<JavaClient, JavaSession> get() = ToOneField(Client_.session())
