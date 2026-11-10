// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.screens.LogScreen

@Composable
fun LogRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val settings by graph.settings.collectAsState()
    val log by graph.relay.log.flow.collectAsState()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LogScreen(present.logUi(tab, log, link), onBack = nav::back, onTab = { tab = it },
        onClear = { graph.relay.log.clear() },
        onCopy = { context.copy(Present.copyText(graph.version, Build.VERSION.RELEASE, relay, link, settings, graph.pairing.value, log)) })
}
