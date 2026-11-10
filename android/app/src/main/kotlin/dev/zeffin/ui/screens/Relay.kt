// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import dev.zeffin.ui.LocalLang
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.Banner
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.FbSwitch
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.Metrics
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.SettingRow
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import dev.zeffin.ui.present.BannerUi
import dev.zeffin.ui.present.Fix
import dev.zeffin.ui.present.RelayUi

@Composable
fun RelayScreen(
    ui: RelayUi,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onFix: (Fix) -> Unit,
    onServer: () -> Unit,
    onDevice: () -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onDiagnostics: () -> Unit,
) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Relay", "Relé"), l.t("Bridge between the server and the Flipper", "Puente entre el servidor y el Flipper"), onBack = onBack)
        PageBody(Modifier.weight(1f)) {
            FbCard(title = l.t("Relay", "Relé"), state = ui.stateTone to ui.stateText, headEnd = {
                androidx.compose.foundation.layout.Box(Modifier.clickable(role = Role.Switch) { onToggle(!ui.on) }) {
                    FbSwitch(ui.on, l.t("Relay on", "Relé activo"))
                }
            }) {
                ui.metrics?.let { m -> item(divider = false) { Metrics(m) } }
                item { InfoRow(l.t("Server", "Servidor"), ui.server.first, ui.server.second) }
                item { InfoRow("Flipper", ui.flipper.first, ui.flipper.second) }
                if (ui.showActions) {
                    item { ActionRow(FbIcons.sync, l.t("Retry", "Reintentar"), onClick = onRetry) }
                    item { ActionRow(FbIcons.chart, l.t("See diagnostics", "Ver diagnóstico"), onClick = onDiagnostics) }
                }
            }
            for (b in ui.banners) BannerView(b, onFix)
            ui.note?.let { Note(it) }
            FbCard(title = l.t("Connection", "Conexión")) {
                item { SettingRow(l.t("Server", "Servidor"), descContent = { StatusDesc(ui.serverDesc) }, onClick = onServer) }
                item { SettingRow(l.t("Device", "Dispositivo"), ui.deviceDesc, onClick = onDevice) }
                item { SettingRow(l.t("Start automatically", "Arranque automático"), l.t("Start the relay when the app opens", "Iniciar el relé al abrir la app"), checked = ui.autoStart, onCheckedChange = onAutoStart) }
            }
            if (ui.lastEvent != null) {
                FbCard { item { SettingRow(l.t("Relay diagnostics", "Diagnóstico del relé"), l.t("Last event: ${ui.lastEvent}", "Último evento: ${ui.lastEvent}"), descMaxLines = 1, onClick = onDiagnostics) } }
            }
        }
    }
}

@Composable
fun BannerView(b: BannerUi, onFix: (Fix) -> Unit) {
    Banner(b.tone, b.title, b.text, b.detail, actions = if (b.fixes.isEmpty()) null else {
        {
            b.fixes.forEachIndexed { i, (fix, label) ->
                if (i > 0) dev.zeffin.ui.components.Divider()
                ActionRow(fixIcon(fix), label) { onFix(fix) }
            }
        }
    })
}

private fun fixIcon(fix: Fix) = when (fix) {
    Fix.RETRY -> FbIcons.sync
    Fix.PAIR_AGAIN -> FbIcons.qr
    Fix.BLUETOOTH_SETTINGS, Fix.ENABLE_BLUETOOTH, Fix.GRANT_BLUETOOTH, Fix.DEVICE -> FbIcons.bluetooth
    Fix.BATTERY -> FbIcons.battery
    Fix.NOTIFICATIONS -> FbIcons.info
    Fix.SERVER -> FbIcons.settings
}
