// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.core.FlipperChoice
import dev.zeffin.relay.RadioState
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.BannerUi
import dev.zeffin.ui.present.DeviceListUi
import dev.zeffin.ui.present.DeviceUi
import dev.zeffin.ui.present.Fix
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.screens.DeviceScreen
import kotlinx.coroutines.launch

@Composable
fun DeviceRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.collectAsState()
    val warnings = rememberWarnings(graph)
    val fixes = rememberFixes(graph, nav, warnings)
    val lang = LocalLang.current
    var refresh by remember { mutableIntStateOf(0) }
    val devices = remember(refresh, warnings.value) { graph.relay.bondedDevices() }
    val banner = when {
        !warnings.value.bluetoothPermission -> BannerUi(Tone.WARNING,
            lang.t("The nearby devices permission is missing", "Falta el permiso de dispositivos cercanos"),
            lang.t("It is needed to see the paired devices and connect to the Flipper.",
                "Hace falta para ver los dispositivos emparejados y conectar con el Flipper."),
            fixes = listOf(Fix.GRANT_BLUETOOTH to lang.t("Grant the permission", "Dar el permiso")))
        graph.relay.radioState() == RadioState.OFF ->
            BannerUi(Tone.WARNING, lang.t("Bluetooth is off", "El Bluetooth está apagado"), null,
                fixes = listOf(Fix.ENABLE_BLUETOOTH to lang.t("Turn on Bluetooth", "Encender el Bluetooth")))
        devices.isEmpty() -> BannerUi(Tone.ACTION, lang.t("No paired devices", "No hay dispositivos emparejados"),
            lang.t("Pair the Flipper in Android's Bluetooth settings.", "Empareja el Flipper en los ajustes Bluetooth de Android."))
        else -> null
    }
    DeviceScreen(
        DeviceListUi(devices.map { DeviceUi(it.name, it.address, it.looksLikeFlipper, it.address == settings.flipper?.address) }, banner),
        onBack = nav::back,
        onRefresh = { refresh++ },
        onUse = { d -> scope.launch { graph.core.settings.setFlipper(FlipperChoice(d.address, d.name)) } },
        onPairNew = { context.startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onFix = fixes.fix,
    )
}
