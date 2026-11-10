// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.Banner
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.present.AgentInfoUi
import dev.zeffin.ui.present.AgentRowUi
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.color

/** A row's description that starts with its state in color, as Relay and Server in Settings: "Ready · 2.1.308 · …". */
@Composable
fun StateParts(parts: List<Pair<Tone, String>>, rest: String? = null) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        parts.forEachIndexed { i, (tone, word) ->
            if (i > 0) {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = FbColors.inkMuted, fontSize = FbType.caption.fontSize))
                append(" · ")
                pop()
            }
            pushStyle(androidx.compose.ui.text.SpanStyle(color = tone.color(), fontSize = FbType.bodyStrong.fontSize,
                fontWeight = FbType.bodyStrong.fontWeight))
            append(word)
            pop()
        }
        if (rest != null) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = FbColors.inkMuted, fontSize = FbType.caption.fontSize))
            append(rest)
            pop()
        }
    }
    androidx.compose.foundation.text.BasicText(text, style = FbType.bodyStrong)
}

/** Settings, Agents: every agent on the server, with its state, version and login; a tap opens its page. */
@Composable
fun AgentsScreen(rows: List<AgentRowUi>, note: String, server: String?, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Agents", "Agentes"), server?.let { l.t("On $it", "En $it") } ?: l.t("On the server", "En el servidor"), onBack = onBack)
        PageBody(Modifier.weight(1f)) {
            FbCard {
                for (r in rows) item {
                    SettingRow(r.name, descContent = { StateParts(listOf(r.state), r.rest) }, onClick = { onOpen(r.id) })
                }
            }
            Note(note)
        }
    }
}

/** One agent's page: its state, version, login and remote access; what free providers keep; why it is not ready. */
@Composable
fun AgentInfoScreen(ui: AgentInfoUi, server: String?, onBack: () -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(ui.name, server?.let { l.t("Agent on $it", "Agente en $it") } ?: l.t("Agent on the server", "Agente en el servidor"), onBack = onBack)
        PageBody(Modifier.weight(1f)) {
            FbCard(title = ui.name, state = ui.state) {
                for ((label, value, tone) in ui.rows) item { InfoRow(label, value, tone = tone) }
                if (ui.privacy != null) item(divider = false) {
                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        Txt(ui.privacy, FbType.caption, color = FbColors.inkMuted)
                    }
                }
            }
            ui.problem?.let { (title, text) -> Banner(Tone.WARNING, title, text) }
            ui.note?.let { Note(it) }
        }
    }
}
