// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.zeffin.AppGraph
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.Routes
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.screens.RelayScreen
import kotlinx.coroutines.launch

@Composable
fun RelayRoute(graph: AppGraph, nav: AppNavigator) {
    val scope = rememberCoroutineScope()
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val settings by graph.settings.collectAsState()
    val pairing by graph.pairing.collectAsState()
    val log by graph.relay.log.flow.collectAsState()
    val warnings = rememberWarnings(graph)
    val fixes = rememberFixes(graph, nav, warnings)
    val now = rememberNow()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    RelayScreen(
        present.relayUi(relay, link, settings, pairing, warnings.value, log.lastOrNull(), now),
        onBack = nav::back,
        onToggle = { on ->
            when {
                on && relay.running -> graph.relay.retryNow()
                on -> fixes.startRelay()
                else -> graph.relay.stop()
            }
        },
        onRetry = { graph.relay.retryNow() },
        onFix = fixes.fix,
        onServer = { nav.go(Routes.RELAY_SERVER) },
        onDevice = { nav.go(Routes.DEVICE) },
        onAutoStart = { scope.launch { graph.core.settings.setAutoStart(it) } },
        onDiagnostics = { nav.go(Routes.DIAGNOSTICS) },
    )
}
