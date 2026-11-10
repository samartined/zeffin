// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.screens.AboutScreen

@Composable
fun AboutRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val notices = remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().readText() }.getOrDefault("") }
    AboutScreen(graph.version, Build.VERSION.RELEASE, notices, onBack = nav::back)
}
