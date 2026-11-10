// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import dev.zeffin.core.Lang
import dev.zeffin.ui.LocalLang
import androidx.compose.ui.Modifier
import dev.zeffin.notify.NotifyService
import dev.zeffin.relay.RelayController
import dev.zeffin.ui.AppNav
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbTheme

class MainActivity : ComponentActivity() {
    private val openRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: black bars with light icons.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        super.onCreate(savedInstanceState)
        // Only on a fresh start: a recreation (rotation, language change) must not jump to Relay again.
        if (savedInstanceState == null) openRoute.value = intent?.getStringExtra(RelayController.EXTRA_OPEN)
        setContent {
            val locales = LocalConfiguration.current.locales
            val lang = Lang.pick((0 until locales.size()).map { locales[it].toLanguageTag() })
            CompositionLocalProvider(LocalLang provides lang) {
                FbTheme {
                    Box(Modifier.fillMaxSize().background(FbColors.surface).safeDrawingPadding()) {
                        AppNav(graph, openRoute)
                    }
                }
            }
        }
    }

    // The activity's own start and stop come ~700 ms before the process's (AppGraph.foreground and background): the
    // foreground service must start while the app is still leaving the screen, inside Android's exemption.
    override fun onStart() {
        super.onStart()
        graph.notifier.setVisible(true)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return  // recreated at once (a language change): still on screen
        graph.notifier.setVisible(false)
        if (graph.notifier.work.value != null) NotifyService.start(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openRoute.value = intent.getStringExtra(RelayController.EXTRA_OPEN)
    }
}
