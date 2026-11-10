// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.notify

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Why /app closes once the app has been off screen for a while. */
enum class LinkRelease {
    /** Nothing runs that notifies. */
    IDLE,
    /** Something runs, but no service holds the app (Android refused it, or it ended): nothing keeps the process
     *  awake, and Doze could freeze the open socket without a disconnect, so the 15 min watch would never fire. */
    NO_SERVICE,
}

/**
 * Off screen, /app stays open [graceMs] in case the app comes back, then for as long as work runs and
 * [NotifyService] holds the app. Returns when it should close, and why.
 */
suspend fun awaitLinkRelease(work: Flow<Work?>, serviceRunning: Flow<Boolean>, graceMs: Long = 30_000): LinkRelease {
    delay(graceMs)
    return combine(work, serviceRunning) { w, running ->
        when {
            w == null -> LinkRelease.IDLE
            !running -> LinkRelease.NO_SERVICE
            else -> null
        }
    }.first { it != null }!!
}
