// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.Banner
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.present.AgentUi
import dev.zeffin.ui.present.AgentPrefsUi
import dev.zeffin.ui.present.Tone
import dev.zeffin.notify.NotifySettingsUi

enum class AgentSheet { NONE, MODEL, EFFORT, MODE, TIMEOUT, AGENT }

/** The Notifications card's actions. */
class NotifyCallbacks(
    val onTurns: (Boolean) -> Unit = {},
    val onPermissions: (Boolean) -> Unit = {},
    val onAllow: () -> Unit = {},
)

@Composable
fun AgentScreen(ui: AgentUi, notify: NotifySettingsUi, onBack: () -> Unit, onSheet: (AgentSheet) -> Unit, notifyCallbacks: NotifyCallbacks = NotifyCallbacks(), scroll: ScrollState = rememberScrollState()) {
    val l = LocalLang.current
    val prefs = ui.prefs ?: AgentPrefsUi(null, ui.model, ui.effort, ui.mode, ui.timeout,
        l.t("Each conversation can change them later, from the chat.", "Cada conversación puede cambiarlos después, desde el chat."))
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Agent", "Agente"), onBack = onBack)
        PageBody(Modifier.weight(1f), scroll) {
            // With the agents listed (Settings, Connections, Agents), this page keeps only preferences; an older server's agent shows here
            if (prefs.agent == null) FbCard(title = ui.name, state = ui.state.tone to ui.state.state) {
                item { InfoRow(l.t("Version", "Versión"), ui.version) }
                item { InfoRow(l.t("Login", "Inicio de sesión"), ui.login, tone = if (ui.ready) Tone.SUCCESS else null) }
                if (ui.state.rest != null) item { InfoRow(l.t("State", "Estado"), ui.state.rest) }
            }
            FbCard(title = l.t("New conversations", "Conversaciones nuevas")) {
                if (prefs.agent != null) item { SettingRow(l.t("Agent", "Agente"), value = prefs.agent, onClick = { onSheet(AgentSheet.AGENT) }) }
                item { SettingRow(l.t("Model", "Modelo"), value = prefs.model, onClick = { onSheet(AgentSheet.MODEL) }) }
                if (prefs.effort != null) item { SettingRow(l.t("Effort", "Esfuerzo"), value = prefs.effort, onClick = { onSheet(AgentSheet.EFFORT) }) }
                item { SettingRow(l.t("Permission mode", "Modo de permisos"), value = prefs.mode, onClick = { onSheet(AgentSheet.MODE) }) }
            }
            Note(prefs.note)
            FbCard(title = l.t("Permissions", "Permisos")) {
                item { SettingRow(l.t("Time to answer", "Tiempo para responder"),
                    l.t("Then the request is denied and the agent goes on", "Después la petición se deniega y el agente sigue"),
                    value = prefs.timeout, onClick = { onSheet(AgentSheet.TIMEOUT) }) }
            }
            if (!notify.allowed) {
                Banner(Tone.WARNING, l.t("Notifications off", "Notificaciones desactivadas"),
                    l.t("Android does not let Zeffin show notifications, so you will not know when the agent finishes or asks for permission.",
                        "Android no deja que Zeffin muestre notificaciones, así que no sabrás cuándo termina el agente o pide permiso.")) {
                    ActionRow(FbIcons.info, l.t("Allow notifications", "Permitir notificaciones"), onClick = notifyCallbacks.onAllow)
                }
            }
            val dim = Modifier.alpha(if (notify.allowed) 1f else 0.45f)
            FbCard(title = l.t("Notifications", "Notificaciones")) {
                item { Box(dim) { SettingRow(l.t("When a turn ends", "Cuando termina un turno"),
                    l.t("Finished, stopped or failed", "Terminado, parado o fallido"),
                    checked = notify.turns, onCheckedChange = notifyCallbacks.onTurns) } }
                item { Box(dim) { SettingRow(l.t("When the agent asks for permission", "Cuando el agente pide permiso"),
                    l.t("Allow once or Deny from the notification", "Permitir una vez o Denegar desde la notificación"),
                    checked = notify.permissions, onCheckedChange = notifyCallbacks.onPermissions) } }
            }
            Note(l.t("Only while the app is not on screen. While the agent works, the notification bar shows “The agent is working”. Always allow and plans are answered in the app.",
                "Solo mientras la app no está en pantalla. Mientras el agente trabaja, la barra de notificaciones muestra «El agente está trabajando». Permitir siempre y los planes se responden en la app."))
        }
    }
}
