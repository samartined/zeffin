// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.Routes
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.screens.DiagnosticsScreen
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val settings by graph.settings.collectAsState()
    val log by graph.relay.log.flow.collectAsState()
    val now = rememberNow()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    var diagAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(link.conn) {
        while (true) {
            if (graph.link.requestDiag()) diagAt = System.currentTimeMillis()
            delay(10_000)
        }
    }
    DiagnosticsScreen(present.diagnosticsUi(relay, link, log, now, diagAt?.takeIf { link.diag != null }),
        onBack = nav::back, onLog = { nav.go(Routes.LOG) },
        onCopy = { context.copy(Present.copyText(graph.version, Build.VERSION.RELEASE, relay, link, settings, graph.pairing.value, log)) })
}
