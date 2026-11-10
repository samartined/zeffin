// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.zeffin.AppGraph
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.Routes
import dev.zeffin.ui.present.PresentAgents
import dev.zeffin.ui.screens.AgentInfoScreen
import dev.zeffin.ui.screens.AgentsScreen

/** Settings, Connections, Agents: every agent the server knows. */
@Composable
fun AgentsRoute(graph: AppGraph, nav: AppNavigator) {
    val chatState by graph.chat.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val lang = LocalLang.current
    val words = remember(lang) { PresentAgents(lang) }
    AgentsScreen(words.rows(chatState), words.rowsNote, link.serverName, onBack = nav::back,
        onOpen = { nav.go(Routes.agentInfo(it)) })
}

/** One agent's page; back to the list when the server no longer lists it. */
@Composable
fun AgentInfoRoute(graph: AppGraph, nav: AppNavigator, id: String) {
    val chatState by graph.chat.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val lang = LocalLang.current
    val words = remember(lang) { PresentAgents(lang) }
    val agent = chatState.agentList.firstOrNull { it.id == id }
    if (agent == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.back() }
        return
    }
    AgentInfoScreen(words.info(agent, chatState.freeOnly), link.serverName, onBack = nav::back)
}
