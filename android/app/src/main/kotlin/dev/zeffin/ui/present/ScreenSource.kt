// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.link.Frame
import dev.zeffin.relay.RelayFrame
import dev.zeffin.relay.RelayState
import dev.zeffin.relay.Stage

/** The frame Home or the remote draws, and whether it is the relay's own (then [Frame.fromRelay] too). */
data class Shown(val frame: Frame?, val fromRelay: Boolean)

/**
 * Which frames Home and the remote draw: the relay's, read on its own link, whenever the
 * relay holds the link, with or without a server; `/app`'s only when it does not (the relay off or still connecting, or
 * another phone's relay on the server). In a server session whose reader has no frame yet (it syncs on the server's
 * settle), `/app`'s carry the same stream. Frames are untrusted data either way: they are only decoded and drawn.
 */
object ScreenSource {
    fun pick(relay: RelayState, relayFrame: RelayFrame?, appFrame: Frame?): Shown = when {
        relay.stage == Stage.REMOTE -> Shown(relayFrame?.let(::frameOf), fromRelay = true)
        relay.stage == Stage.RUNNING && relayFrame != null -> Shown(frameOf(relayFrame), fromRelay = true)
        else -> Shown(appFrame, fromRelay = false)
    }

    private fun frameOf(f: RelayFrame) =
        Frame(f.seq, f.orientation, stale = false, data = f.data, receivedAt = f.receivedAt, fromRelay = true)
}
