// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.ScreenOrientation

data class ServerUi(
    val title: String,
    val state: Pair<Tone, String>,
    val rows: List<RowUi>,
    val orientation: ScreenOrientation,
    val banners: List<BannerUi>,
    val canOpenView: Boolean,
    /** The pinned key's fingerprint, for comparing with the browser's warning on /view. */
    val fingerprint: String? = null,
)

/** One label and value row of a card. */
data class RowUi(val label: String, val value: String, val tone: Tone? = null, val mono: Boolean = false)

/** What the Server screen's rows and buttons do. */
data class ServerActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onPair: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onOrientation: () -> Unit = {},
    val onPickOrientation: (ScreenOrientation?) -> Unit = {},
    val onOpenView: () -> Unit = {},
    val onFix: (Fix) -> Unit = {},
)
