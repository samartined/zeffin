// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.core.Addresses
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.Routes
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.ServerActions
import dev.zeffin.ui.screens.ServerScreen
import kotlinx.coroutines.launch

@Composable
fun ServerRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by graph.link.state.collectAsState()
    val settings by graph.settings.collectAsState()
    val pairing by graph.pairing.collectAsState()
    val fixes = rememberFixes(graph, nav, rememberWarnings(graph))
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    ServerScreen(present.serverUi(link, settings, pairing), choosing, ServerActions(
        onBack = nav::back,
        onRetry = { graph.link.retry() },
        onPair = { nav.go(Routes.PAIR) },
        onPaste = { nav.go("${Routes.PAIR}?paste=1") },
        onOrientation = { choosing = true },
        onPickOrientation = { o ->
            choosing = false
            if (o != null) scope.launch { graph.core.settings.setOrientation(o) }
        },
        onOpenView = { pairing?.base?.let { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(Addresses.viewUrl(it).toString()))) } },
        onFix = fixes.fix,
    ))
}
