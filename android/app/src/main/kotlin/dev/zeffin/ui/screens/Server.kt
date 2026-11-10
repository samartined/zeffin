// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.zeffin.core.ScreenOrientation
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.core.Lang
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.present.Fix
import dev.zeffin.ui.present.ServerActions
import dev.zeffin.ui.present.ServerUi

fun ScreenOrientation.label(l: Lang) = when (this) {
    ScreenOrientation.AUTO -> l.t("Automatic", "Automática")
    ScreenOrientation.HORIZONTAL -> l.t("Always landscape", "Siempre horizontal")
}

@Composable
fun ServerScreen(ui: ServerUi, choosingOrientation: Boolean, actions: ServerActions) {
    val l = LocalLang.current
    val onPickOrientation = actions.onPickOrientation
    Box(Modifier.fillMaxSize().background(FbColors.surface)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(l.t("Server", "Servidor"), onBack = actions.onBack)
            PageBody(Modifier.weight(1f)) {
                for (b in ui.banners) BannerView(b, actions.onFix)
                FbCard(title = ui.title, state = ui.state) {
                    for (row in ui.rows) item { InfoRow(row.label, row.value, row.tone, mono = row.mono) }
                    item { ActionRow(FbIcons.sync, l.t("Try again", "Probar de nuevo"), onClick = actions.onRetry) }
                }
                FbCard(title = l.t("Configuration", "Configuración")) {
                    item { SettingRow(l.t("Pair with QR code", "Emparejar con el QR"),
                        l.t("Scan the QR code of the web view or zf pair", "Escanea el QR de la vista web o de zf pair"), onClick = actions.onPair) }
                    item { SettingRow(l.t("Paste the pairing text", "Pegar el texto de emparejamiento"),
                        l.t("Copied from the web view or zf pair", "Copiado de la vista web o de zf pair"), onClick = actions.onPaste) }
                    item { SettingRow(l.t("Screen orientation", "Orientación de la pantalla"), value = ui.orientation.label(l), onClick = actions.onOrientation) }
                }
                FbCard(title = l.t("Backup web view", "Vista web de respaldo")) {
                    item { ActionRow(FbIcons.open, l.t("Open /view in the browser", "Abrir /view en el navegador"), enabled = ui.canOpenView, onClick = actions.onOpenView) }
                }
                ui.fingerprint?.let {
                    Note(l.t("The browser warns about the server's certificate once: its key must be $it.",
                        "El navegador avisa una vez del certificado del servidor: su clave tiene que ser $it."))
                }
            }
        }
        if (choosingOrientation) {
            FbSheet(l.t("Screen orientation", "Orientación de la pantalla"),
                l.t("How the Flipper screen is drawn in Home", "Cómo se dibuja la pantalla del Flipper en Inicio"), onDismiss = { onPickOrientation(null) }) {
                for (o in ScreenOrientation.entries) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton) { onPickOrientation(o) }.padding(12.dp)) {
                        Column(Modifier.align(Alignment.CenterStart)) {
                            Txt(o.label(l), FbType.heading)
                            Txt(if (o == ScreenOrientation.AUTO) l.t("Turns with the Flipper", "Gira con el Flipper")
                                else l.t("Ignores the Flipper's vertical turn", "Ignora el giro vertical del Flipper"),
                                FbType.caption, color = FbColors.inkMuted)
                        }
                        if (o == ui.orientation) Icon(FbIcons.check, FbColors.action, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}
