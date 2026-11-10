// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.Manifest
import android.app.LocaleManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.zeffin.AppGraph
import dev.zeffin.core.AppSettings
import dev.zeffin.core.Lang
import dev.zeffin.core.ScreenOrientation
import dev.zeffin.link.Frame
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.Routes
import dev.zeffin.ui.present.Fix
import dev.zeffin.ui.present.Warnings
import kotlinx.coroutines.delay

/** A clock that ticks every second, for "hace 2 s" and the relay's running time. */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** What Android allows the app now (permissions, battery), read again whenever the screen comes back. */
@Composable
internal fun rememberWarnings(graph: AppGraph): MutableState<Warnings> {
    val context = LocalContext.current
    val warnings = remember { mutableStateOf(graph.warnings(context)) }
    LifecycleResumeEffect(Unit) {
        warnings.value = graph.warnings(context)
        onPauseOrDispose { }
    }
    return warnings
}

/** What a banner's fix does, and starting the relay; the permission requests are this route's own. */
internal class Fixes(val fix: (Fix) -> Unit, val startRelay: () -> Unit, val allowNotifications: () -> Unit)

@Composable
internal fun rememberFixes(graph: AppGraph, nav: AppNavigator, warnings: MutableState<Warnings>): Fixes {
    val context = LocalContext.current
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        warnings.value = graph.warnings(context)
        if (granted) graph.relay.retryNow() else context.openAppSettings()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        warnings.value = graph.warnings(context)
        if (!it) context.openAppSettings()
    }

    val askBluetooth = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
    val askNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    fun fix(f: Fix) {
        when (f) {
            Fix.RETRY -> graph.relay.retryNow()
            Fix.PAIR_AGAIN -> nav.go(Routes.PAIR)
            Fix.SERVER -> nav.go(Routes.RELAY_SERVER)
            Fix.DEVICE -> nav.go(Routes.DEVICE)
            Fix.GRANT_BLUETOOTH -> askBluetooth()
            Fix.NOTIFICATIONS -> askNotifications()
            else -> context.androidFix(f, warnings.value, askBluetooth)
        }
    }

    fun startRelay() {
        if (!warnings.value.bluetoothPermission) askBluetooth()
        if (!warnings.value.notifications) askNotifications()
        graph.relay.start()
    }

    return Fixes(::fix, ::startRelay, askNotifications)
}

/** The fixes that open one of Android's own screens. */
private fun Context.androidFix(f: Fix, warnings: Warnings, askBluetooth: () -> Unit) {
    when (f) {
        Fix.BLUETOOTH_SETTINGS -> startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        Fix.ENABLE_BLUETOOTH -> if (warnings.bluetoothPermission) startSafely(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) else askBluetooth()
        Fix.BATTERY -> if (warnings.backgroundRestricted) openAppSettings()
            else startSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        else -> {}
    }
}

internal fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

internal fun Context.openAppSettings() =
    startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

internal fun Context.copy(text: String, label: String = "Zeffin diagnostics") {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The language picked inside the app, or null when it follows the phone. */
internal fun appLanguage(context: Context): Lang? {
    val chosen = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (chosen.isEmpty) null else Lang.pick((0 until chosen.size()).map { chosen[it].toLanguageTag() })
}

/** Android keeps the per-app language (and shows it in its own settings); null follows the phone. */
internal fun setAppLanguage(context: Context, lang: Lang?) {
    context.getSystemService(LocaleManager::class.java).applicationLocales = when (lang) {
        null -> LocaleList.getEmptyLocaleList()
        Lang.EN -> LocaleList.forLanguageTags("en")
        Lang.ES -> LocaleList.forLanguageTags("es")
    }
}

/** The screen's orientation as drawn: with "Always landscape", a portrait frame is shown turned back. */
internal fun shownOrientation(settings: AppSettings, frame: Frame?): Int =
    if (settings.orientation == ScreenOrientation.HORIZONTAL) (frame?.orientation ?: 0).let { if (it == 1) 1 else 0 } else frame?.orientation ?: 0
