// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import dev.zeffin.AppGraph
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.Routes
import dev.zeffin.ui.present.Offline
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.components.GateActions
import dev.zeffin.ui.present.PresentFlipperRemote
import dev.zeffin.ui.present.ScreenSource
import dev.zeffin.ui.screens.CardActions
import dev.zeffin.ui.screens.HomeActions
import dev.zeffin.ui.screens.HomeScreen
import dev.zeffin.ui.screens.HomeView
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(graph: AppGraph, nav: AppNavigator) {
    val scope = rememberCoroutineScope()
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val appFrame by graph.link.frame.collectAsState()
    val relayFrame by graph.relay.frames.collectAsState()
    val settings by graph.settings.collectAsState()
    val pairing by graph.pairing.collectAsState()
    val chatState by graph.chat.state.collectAsState()
    val streaming by graph.chat.streaming.collectAsState()
    val now = rememberNow()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    val presentChat = remember(lang, chatState.agentName) { PresentChat(lang, chatState.agentName) }
    // the relay's own frames whenever it holds the link, /app's otherwise (ScreenSource)
    val frame = ScreenSource.pick(relay, relayFrame, appFrame).frame
    val home = present.homeUi(relay, link, frame, settings, pairing, now)
    val remote = remember(lang) { PresentFlipperRemote(lang) }
    HomeScreen(
        home,
        HomeView(frame?.data, shownOrientation(settings, frame), LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE),
        HomeActions(
            gate = GateActions(
                onPause = { graph.link.gate("pause") },
                onResume = { graph.link.gate("resume") },
                remote = remote.homeButton to { nav.go(Routes.REMOTE) },
            ),
            onOpenRelay = { nav.go(Routes.RELAY) },
            onOpenServer = { nav.go(if (pairing == null || home.offline == Offline.PAIR_AGAIN) Routes.PAIR else Routes.SERVER) },
            onTab = nav::tab,
            card = CardActions(
                onOpenConversation = { id ->
                    if (id != null && id != chatState.open?.id) graph.chat.open(id)
                    nav.openConversation()
                },
                onNewConversation = { graph.chat.newConversation { scope.launch { nav.openConversation() } } },
                onAnswer = { id, answer -> graph.chat.answer(id, answer) },
            ),
        ),
        last = presentChat.lastConversation(chatState, streaming),
    )
}
