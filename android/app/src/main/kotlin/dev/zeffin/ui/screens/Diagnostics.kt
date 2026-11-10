// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import dev.zeffin.ui.LocalLang
import dev.zeffin.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.zeffin.ui.theme.color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.FbButton
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.IconButton
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.Divider
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.TabBand
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.present.DiagnosticsUi
import dev.zeffin.ui.present.LogLineUi
import dev.zeffin.ui.present.LogUi

@Composable
fun DiagnosticsScreen(ui: DiagnosticsUi, onBack: () -> Unit, onLog: () -> Unit, onCopy: () -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Diagnostics", "Diagnóstico"), ui.updated, onBack = onBack) {
            IconButton(FbIcons.list, l.t("Open the full log", "Abrir el registro completo"), onClick = onLog)
        }
        PageBody(Modifier.weight(1f)) {
            FbCard(title = l.t("State", "Estado")) { for ((label, v, t) in ui.state) item { InfoRow(label, v, t) } }
            FbCard(title = l.t("Server", "Servidor")) {
                if (ui.server == null) item { InfoRow(l.t("Figures", "Cifras"), l.t("no connection with the server", "sin conexión con el servidor")) }
                else for ((label, v, t) in ui.server) item { InfoRow(label, v, t) }
            }
            FbCard(title = l.t("Latest events", "Últimos eventos")) {
                if (ui.recent.isNotEmpty()) item(divider = false) { LogLines(ui.recent) }
                item { ActionRow(FbIcons.list, l.t("See the full log", "Ver el registro completo"), onClick = onLog) }
            }
        }
        FbButton(l.t("Copy diagnostics", "Copiar diagnóstico"), Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), onClick = onCopy)
    }
}

/**
 * Log lines, whole: each scrolls sideways with the rest instead of ending in "…", and the text can be selected. Each
 * event is one text, its columns lined up with spaces in the mono font, so a copied selection keeps one event per line.
 */
@Composable
private fun LogLines(lines: List<LogLineUi>) {
    val timeWidth = lines.maxOf { it.time.length }
    val levelWidth = lines.maxOf { it.level.length }
    SelectionContainer {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = maxWidth
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = cardWidth)) {
                    for ((i, r) in lines.withIndex()) {
                        if (i > 0) Divider()
                        val text = buildAnnotatedString {
                            withStyle(SpanStyle(color = FbColors.inkFaint)) { append(r.time.padEnd(timeWidth)) }
                            append("  ")
                            withStyle(SpanStyle(color = r.tone.color())) { append(r.level.padEnd(levelWidth)) }
                            append("  ")
                            append(r.text)
                        }
                        BasicText(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = FbType.monoSmall,
                            softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}

@Composable
fun LogScreen(ui: LogUi, onBack: () -> Unit, onTab: (Int) -> Unit, onClear: () -> Unit, onCopy: () -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Log", "Registro"), onBack = onBack) {
            if (ui.tab == 0) IconButton(FbIcons.trash, l.t("Clear the log", "Vaciar el registro"), onClick = onClear)
        }
        TabBand(listOf(l.t("Relay", "Relé"), l.t("Server", "Servidor")), ui.tab, onSelect = onTab)
        PageBody(Modifier.weight(1f)) {
            FbCard {
                if (ui.lines.isEmpty()) item { InfoRow(l.t("Log", "Registro"), l.t("empty", "vacío")) }
                else item(divider = false) { LogLines(ui.lines) }
            }
            Note(l.t("Tokens never appear in the log. Copying includes the version, transport and the last 200 events.",
                "Los tokens nunca aparecen en el registro. Al copiar se incluyen versión, transporte y los últimos 200 eventos."))
        }
        FbButton(l.t("Copy diagnostics", "Copiar diagnóstico"), Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), onClick = onCopy)
    }
}

/** THIRD_PARTY_NOTICES.md as plain text: headings, bullets and paragraphs, without Markdown marks. */
fun noticeBlocks(markdown: String): List<Pair<Boolean, String>> {
    val blocks = mutableListOf<Pair<Boolean, String>>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) blocks += false to para.toString().trim()
        para.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.replace("**", "").replace("`", "")
        when {
            line.startsWith("#") -> { flush(); blocks += true to line.trimStart('#').trim() }
            line.isBlank() -> flush()
            line.startsWith("- ") -> { flush(); para.append("• ").append(line.removePrefix("- ")) }
            else -> para.append(if (para.isEmpty()) "" else " ").append(line.trim())
        }
    }
    flush()
    return blocks
}

/** The copyright line, the same in both languages (LICENSE and the README's footer carry it too). */
private const val COPYRIGHT = "© 2026 Edgar Espinosa Martín (@samartined)"

@Composable
fun AboutScreen(version: String, android: String, notices: String, onBack: () -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("About", "Acerca de"), onBack = onBack)
        PageBody(Modifier.weight(1f)) {
            FbCard(title = "Zeffin") {
                item { InfoRow(l.t("Version", "Versión"), version) }
                item { InfoRow("Android", android) }
                item { InfoRow(l.t("Identifier", "Identificador"), BuildConfig.APPLICATION_ID, mono = true) }
                item {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(l.t("Zeffin is free software under the GNU AGPL version 3 or later.",
                            "Zeffin es software libre bajo la GNU AGPL versión 3 o posterior."), FbType.caption, color = FbColors.inkMuted)
                        Txt(COPYRIGHT, FbType.caption, color = FbColors.inkMuted)
                        Txt(l.t("Not affiliated with Flipper Devices or Anthropic.",
                            "Sin relación con Flipper Devices ni con Anthropic."), FbType.caption, color = FbColors.inkMuted)
                    }
                }
            }
            FbCard(title = l.t("Third-party licenses", "Licencias de terceros")) {
                item(divider = false) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((heading, text) in noticeBlocks(notices).drop(1)) {
                            if (heading) Txt(text, FbType.bodyStrong, Modifier.padding(top = 4.dp))
                            else Txt(text, FbType.caption, color = FbColors.inkMuted)
                        }
                    }
                }
            }
        }
    }
}
