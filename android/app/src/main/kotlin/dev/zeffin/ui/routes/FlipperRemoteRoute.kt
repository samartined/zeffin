// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import dev.zeffin.AppGraph
import dev.zeffin.core.ScreenOrientation
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.AppProtocol.GateVia
import dev.zeffin.link.Conn
import dev.zeffin.relay.Stage
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.PresentFlipperRemote
import dev.zeffin.ui.present.RemoteAction
import dev.zeffin.ui.present.RemoteGate
import dev.zeffin.ui.present.RemoteInputs
import dev.zeffin.ui.present.ScreenSource
import dev.zeffin.ui.screens.FlipperRemoteScreen
import dev.zeffin.ui.screens.RemoteActions
import dev.zeffin.ui.screens.RemoteView

/**
 * The remote. With a server session and `/app` connected, opening it pauses an
 * active agent with `via: "remote"`; the keys work once the gate reads paused. Leaving offers Resume only when the remote
 * paused it. The keys go over the relay's own link (RelayController.remote), never through `/app`.
 *
 * While it is open the relay knows it (`remote.open()`): without a server session it holds the Flipper for the remote
 * alone and does not dial `/phone`, so the keys work at once and the agent cannot start in the middle. With the relay
 * off, the remote asks to start it (Start the relay) rather than starting the foreground service by itself.
 */
@Composable
fun FlipperRemoteRoute(graph: AppGraph, nav: AppNavigator) {
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val keys by graph.relay.remote.keys.collectAsState()
    val refused by graph.relay.remote.refused.collectAsState()
    val relayFrame by graph.relay.frames.collectAsState()
    val appFrame by graph.link.frame.collectAsState()  // drawn only when the relay does not hold the link
    val settings by graph.settings.collectAsState()
    val pairing by graph.pairing.collectAsState()
    val lang = LocalLang.current
    val words = remember(lang) { PresentFlipperRemote(lang) }
    var gate by remember { mutableStateOf(RemoteGate()) }
    // A refusal from before the remote opened, or before the last key, is not shown.
    var refusalFrom by remember { mutableLongStateOf(graph.relay.remote.refused.value?.serial ?: 0L) }
    var leaving by remember { mutableStateOf(false) }
    val now = rememberNow()
    DisposableEffect(Unit) { graph.relay.remote.open(); onDispose { graph.relay.remote.close() } }
    val frame = ScreenSource.pick(relay, relayFrame, appFrame).frame

    LaunchedEffect(link, relay.stage) {
        if (relay.stage != Stage.RUNNING) return@LaunchedEffect  // without a server session there is nothing to pause
        val (next, pause) = gate.next(link, System.currentTimeMillis())
        // A pause that could not be sent is not marked as started: the next look at the link sends it.
        if (!pause || graph.link.gate("pause", GateVia.REMOTE)) gate = next
    }
    val leave = {
        if (gate.offersResume(link)) leaving = true else nav.back()
    }
    BackHandler(enabled = !leaving, onBack = leave)

    val ui = words.ui(RemoteInputs(relay, link, gate, keys, refused, showRefusal = (refused?.serial ?: 0L) > refusalFrom,
        hasFrame = frame != null, nowMs = now, paired = pairing != null))
    val raw = frame?.orientation ?: 0
    val orientation = if (settings.orientation == ScreenOrientation.HORIZONTAL) (if (raw == 1) 1 else 0) else raw
    val view = RemoteView(frame?.data, orientation, LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE, leaving)
    FlipperRemoteScreen(ui, words, view, RemoteActions(
        onBack = leave,
        onKey = { key, long ->
            refusalFrom = graph.relay.remote.refused.value?.serial ?: 0L
            graph.relay.remote.press(key, long)
        },
        onAction = { gate = act(ui.action, link, gate, graph) },
        onResume = {
            graph.link.gate("resume", GateVia.REMOTE)
            leaving = false
            nav.back()
        },
        onKeepPaused = {
            leaving = false
            nav.back()
        },
        onDismissLeave = { leaving = false },
    ))
}

/** The remote's action: start the relay, reconnect `/app`, or pause once more; a pause that was not sent changes nothing. */
private fun act(action: RemoteAction?, link: AppLinkState, gate: RemoteGate, graph: AppGraph): RemoteGate {
    when {
        action == RemoteAction.START_RELAY -> graph.relay.start()
        action == RemoteAction.RETRY && (link.conn != Conn.CONNECTED || link.status == null) -> graph.link.retry()
        action != null && graph.link.gate("pause", GateVia.REMOTE) -> return gate.pauseAgain(System.currentTimeMillis())
    }
    return gate
}
