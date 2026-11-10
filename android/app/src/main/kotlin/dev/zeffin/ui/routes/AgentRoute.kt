// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.zeffin.AppGraph
import dev.zeffin.link.ChatSettings
import dev.zeffin.link.Effort
import dev.zeffin.notify.NotifyPrefs
import dev.zeffin.notify.PresentNotify
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.EffortDial
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.present.OptionUi
import dev.zeffin.ui.present.PresentAgentChoice
import dev.zeffin.link.ChatState
import androidx.compose.foundation.layout.BoxScope
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.screens.AgentScreen
import dev.zeffin.ui.screens.AgentSheet
import dev.zeffin.ui.screens.NotifyCallbacks
import dev.zeffin.ui.screens.OptionSheet
import kotlinx.coroutines.launch

@Composable
fun AgentRoute(graph: AppGraph, nav: AppNavigator) {
    val scope = rememberCoroutineScope()
    val settings by graph.settings.collectAsState()
    val chatState by graph.chat.state.collectAsState()
    val warnings = rememberWarnings(graph)
    val fixes = rememberFixes(graph, nav, warnings)
    val lang = LocalLang.current
    val presentChat = remember(lang, chatState.agentName) { PresentChat(lang, chatState.agentName) }
    val choice = remember(lang) { PresentAgentChoice(lang) }
    var sheet by rememberSaveable { mutableStateOf(AgentSheet.NONE) }
    Box(Modifier.fillMaxSize()) {
        AgentScreen(presentChat.agentUi(chatState).copy(prefs = choice.prefs(chatState, presentChat).takeIf { it.agent != null }),
            PresentNotify(lang).settingsUi(NotifyPrefs(settings.notifyTurns, settings.notifyPermissions), warnings.value.notifications),
            onBack = nav::back, onSheet = { sheet = it },
            notifyCallbacks = NotifyCallbacks(
                onTurns = { on -> scope.launch { graph.core.settings.setNotifyTurns(on) } },
                onPermissions = { on -> scope.launch { graph.core.settings.setNotifyPermissions(on) } },
                onAllow = fixes.allowNotifications))
        AgentSheets(graph, chatState, sheet) { sheet = it }
    }
}

/** Where new conversations start, for the default agent: its id, its models and modes, and its own defaults. */
private class DefaultAgent(state: ChatState) {
    private val base = state.status?.settings ?: ChatSettings()
    val id = base.defaultAgent?.takeIf { id -> state.agentList.any { it.id == id } } ?: state.agentList.firstOrNull()?.id
    val status = state.status?.forAgent(id)
    val settings = id?.let { base.agents[it] }?.let { base.copy(defaultMode = it.mode, defaultModel = it.model, defaultEffort = it.effort) } ?: base
}

/** The sheets of Settings, Agent: every choice is the default agent's (each agent keeps its own defaults). */
@Composable
private fun BoxScope.AgentSheets(graph: AppGraph, chatState: ChatState, sheet: AgentSheet, onSheet: (AgentSheet) -> Unit) {
    val lang = LocalLang.current
    val presentChat = remember(lang, chatState.agentName) { PresentChat(lang, chatState.agentName) }
    val choice = remember(lang) { PresentAgentChoice(lang) }
    val agent = DefaultAgent(chatState)
    val status = agent.status
    val s = agent.settings
    val sub = lang.t("New conversations", "Conversaciones nuevas")
    val done = { onSheet(AgentSheet.NONE) }
    when (sheet) {
        AgentSheet.NONE -> {}
        AgentSheet.AGENT -> OptionSheet(lang.t("Agent", "Agente"), choice.options(chatState, agent.id),
            choice.optionsNote(chatState), sub) { pick -> pick?.let(graph.chat::defaultAgent); done() }
        AgentSheet.MODEL -> OptionSheet(lang.t("Model", "Modelo"), presentChat.modelOptions(status, s.defaultModel), null, sub) { pick ->
            pick?.let { graph.chat.settings(model = it, agent = agent.id) }; done()
        }
        AgentSheet.MODE -> OptionSheet(lang.t("Permission mode", "Modo de permisos"), presentChat.modeOptions(status, s.defaultMode), null, sub) { pick ->
            pick?.let { graph.chat.settings(mode = it, agent = agent.id) }; done()
        }
        AgentSheet.TIMEOUT -> OptionSheet(lang.t("Time to answer", "Tiempo para responder"),
            listOf(30, 60, 120, 300, 570).map { OptionUi(it, presentChat.timeoutName(it), null, it == s.permissionTimeoutS) }, null, sub) { pick ->
            pick?.let { graph.chat.settings(permissionTimeoutS = it) }; done()
        }
        AgentSheet.EFFORT -> FbSheet(lang.t("Effort", "Esfuerzo"), sub, onDismiss = done) {
            val levels = status?.model(s.defaultModel)?.efforts ?: Effort.entries
            EffortDial(levels, s.defaultEffort.takeIf { it in levels } ?: levels.first(), presentChat::effortName, presentChat::effortDesc,
                onLevel = { graph.chat.settings(effort = it, agent = agent.id) })
        }
    }
}
