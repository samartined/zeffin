// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.zeffin.AppGraph
import dev.zeffin.core.Lang
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.Routes
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentAgentChoice
import dev.zeffin.ui.present.PresentAgents
import dev.zeffin.ui.screens.SettingsActions
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.screens.SettingsScreen
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType

@Composable
fun SettingsRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val settings by graph.settings.collectAsState()
    val pairing by graph.pairing.collectAsState()
    val chatState by graph.chat.state.collectAsState()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    val presentChat = remember(lang, chatState.agentName) { PresentChat(lang, chatState.agentName) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(appLanguage(context)) }
    Box(Modifier.fillMaxSize()) {
        val agentWords = remember(lang) { PresentAgents(lang) }
        val choice = remember(lang) { PresentAgentChoice(lang) }
        val listed = chatState.agentList.isNotEmpty()
        SettingsScreen(present.settingsUi(relay, link, settings, pairing, graph.version)
            .copy(agent = presentChat.agentLine(chatState), language = present.languageLabel(chosen),
                agents = if (listed) agentWords.summary(chatState) else null,
                agentPrefs = if (listed) choice.prefsLine(chatState, presentChat) else null),
            SettingsActions(onRelay = { nav.go(Routes.RELAY) }, onServer = { nav.go(Routes.SERVER) },
                onDiagnostics = { nav.go(Routes.DIAGNOSTICS) }, onAbout = { nav.go(Routes.ABOUT) },
                onLanguage = { choosing = true }, onTab = nav::tab, onAgent = { nav.go(Routes.AGENT) }, onAgents = { nav.go(Routes.AGENTS) }))
        if (choosing) LanguageSheet(chosen, present) { pick ->
            choosing = false
            if (pick != null) {
                chosen = pick.lang
                setAppLanguage(context, pick.lang) // Android recreates the activity in the new language
                graph.relay.refreshLang()
            }
        }
    }
}

private class LanguagePick(val lang: Lang?)

@Composable
private fun BoxScope.LanguageSheet(chosen: Lang?, present: Present, onPick: (LanguagePick?) -> Unit) {
    val l = LocalLang.current
    FbSheet("Language · Idioma", l.t("The app's language", "El idioma de la app"), onDismiss = { onPick(null) }) {
        for (option in listOf(null, Lang.EN, Lang.ES)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton) { onPick(LanguagePick(option)) }.padding(12.dp)) {
                Txt(when (option) {
                    null -> l.t("System", "Sistema")
                    else -> present.languageLabel(option)
                }, FbType.heading, Modifier.align(Alignment.CenterStart))
                if (option == chosen) Icon(FbIcons.check, FbColors.action, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
