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
import dev.zeffin.ui.components.DeviceCard
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.GroupTitle
import dev.zeffin.ui.components.IconButton
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.present.DeviceListUi
import dev.zeffin.ui.present.DeviceUi
import dev.zeffin.ui.present.Fix

@Composable
fun DeviceScreen(ui: DeviceListUi, onBack: () -> Unit, onRefresh: () -> Unit, onUse: (DeviceUi) -> Unit, onPairNew: () -> Unit, onFix: (Fix) -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Device", "Dispositivo"), l.t("Choose the Flipper it connects to", "Elige el Flipper con el que se conecta"), onBack = onBack) {
            IconButton(FbIcons.refresh, l.t("Look again", "Volver a buscar"), onClick = onRefresh)
        }
        PageBody(Modifier.weight(1f)) {
            ui.banner?.let { BannerView(it, onFix) }
            val flippers = ui.devices.filter { it.looksLikeFlipper }
            val others = ui.devices.filterNot { it.looksLikeFlipper }
            if (flippers.isNotEmpty()) {
                GroupTitle(l.t("Look like a Flipper", "Parecen un Flipper"))
                for (d in flippers) DeviceCard(d.name, d.address, true, d.current) { onUse(d) }
            }
            if (others.isNotEmpty()) {
                GroupTitle(l.t("Other paired devices", "Otros emparejados"))
                for (d in others) DeviceCard(d.name, d.address, false, d.current) { onUse(d) }
            }
            FbCard { item { ActionRow(FbIcons.plus, l.t("Pair a new Flipper", "Emparejar un Flipper nuevo"), onClick = onPairNew) } }
            Note(l.t("Opens Android's Bluetooth settings. All paired devices are shown because the Flipper may advertise another name; if you renamed it, pair it again.",
                "Abre los ajustes Bluetooth de Android. Se muestran todos los emparejados porque el Flipper puede anunciarse con otro nombre; si se lo cambiaste, vuelve a emparejarlo."))
        }
    }
}
