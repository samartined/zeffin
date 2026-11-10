// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

/** What a banner offers to fix the problem. */
enum class Fix { RETRY, PAIR_AGAIN, BLUETOOTH_SETTINGS, ENABLE_BLUETOOTH, GRANT_BLUETOOTH, BATTERY, NOTIFICATIONS, SERVER, DEVICE }

data class BannerUi(val tone: Tone, val title: String, val text: String?, val detail: String? = null, val fixes: List<Pair<Fix, String>> = emptyList())

data class RelayUi(
    val stateTone: Tone,
    val stateText: String,
    val on: Boolean,
    val metrics: List<Pair<String, String>>?,
    val server: Pair<String, Tone?>,
    val flipper: Pair<String, Tone?>,
    val showActions: Boolean,
    val banners: List<BannerUi>,
    val note: String?,
    val serverDesc: StatusLine,
    val deviceDesc: String,
    val autoStart: Boolean,
    val lastEvent: String?,
)
