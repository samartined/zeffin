// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.link.AppLink
import dev.zeffin.link.LinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Subscribes to the link's events at once, as AppGraph does before the link connects, and hands each one on in the
 * thread that emitted it, so a test sees it as soon as `handle` returns.
 */
fun AppLink.onEvents(handle: (LinkEvent) -> Unit): Job =
    CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { events.collect(handle) }
