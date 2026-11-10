// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zeffin.ui.components.Banner
import dev.zeffin.ui.components.BottomNav
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.StateText
import dev.zeffin.ui.components.Tab
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.theme.color
import dev.zeffin.ui.present.SettingsUi
import dev.zeffin.ui.present.StatusLine
import dev.zeffin.ui.present.Tone

@Composable
fun StatusDesc(line: StatusLine) {
    Row {
        Txt(line.state, if (line.tone == Tone.NEUTRAL) FbType.caption else FbType.bodyStrong, color = line.tone.color(), maxLines = 1)
        if (line.rest != null) Txt(" · ${line.rest}", FbType.caption, color = FbColors.inkMuted, maxLines = 1)
    }
}

/** Where each row of Settings goes. */
data class SettingsActions(
    val onRelay: () -> Unit = {},
    val onServer: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onAbout: () -> Unit = {},
    val onLanguage: () -> Unit = {},
    val onTab: (Tab) -> Unit = {},
    val onAgent: () -> Unit = {},
    /** Connections' Agents row. */
    val onAgents: () -> Unit = {},
)

@Composable
fun SettingsScreen(ui: SettingsUi, actions: SettingsActions = SettingsActions()) {
    val l = LocalLang.current
    val onRelay = actions.onRelay
    val onServer = actions.onServer
    val onAgent = actions.onAgent
    val onAgents = actions.onAgents
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Settings", "Ajustes"))
        PageBody(Modifier.weight(1f)) {
            FbCard(title = l.t("Connections", "Conexiones")) {
                item { SettingRow(l.t("Relay", "Relé"), descContent = { StatusDesc(ui.relay) }, onClick = onRelay) }
                item { SettingRow(l.t("Server", "Servidor"), descContent = { StatusDesc(ui.server) }, onClick = onServer) }
                if (!ui.agents.isNullOrEmpty()) item { SettingRow(l.t("Agents", "Agentes"), descContent = { StateParts(ui.agents) }, onClick = onAgents) }
            }
            if (ui.agentPrefs != null) FbCard(title = l.t("Agent", "Agente")) {
                item { SettingRow(l.t("Agent", "Agente"), ui.agentPrefs, onClick = onAgent) }
            } else if (ui.agent != null) FbCard(title = l.t("Agent", "Agente")) {
                item { SettingRow(l.t("Agent", "Agente"), descContent = { StatusDesc(ui.agent) }, onClick = onAgent) }
            }
            FbCard(title = l.t("App", "App")) {
                item { SettingRow("Language · Idioma", value = ui.language, onClick = actions.onLanguage) }
            }
            FbCard(title = l.t("Tools", "Herramientas")) {
                item { SettingRow(l.t("Diagnostics", "Diagnóstico"), l.t("Relay log and server figures", "Registro del relé y métricas del servidor"), onClick = actions.onDiagnostics) }
                item { SettingRow(l.t("About", "Acerca de"), l.t("Zeffin ${ui.version} · licenses", "Zeffin ${ui.version} · licencias"), onClick = actions.onAbout) }
            }
        }
        BottomNav(Tab.SETTINGS, ui.nav, actions.onTab)
    }
}
