// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zeffin.graph
import dev.zeffin.link.PermissionAnswer

/**
 * Allow once and Deny from a permission's notification. A receiver, never an activity, so Deny works from the lock
 * screen without unlocking; Allow once asks Android to unlock first (setAuthenticationRequired).
 */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val answer = PermissionAnswer.entries.firstOrNull { it.wire == intent.getStringExtra(EXTRA_ANSWER) } ?: return
        if (answer == PermissionAnswer.ALWAYS) return  // "always" is answered in the app, where its rule is shown
        context.graph.notifier.answerFromNotification(id, answer)
    }

    companion object {
        const val ACTION_ANSWER = "dev.zeffin.notify.ANSWER"
        const val EXTRA_ID = "id"
        const val EXTRA_ANSWER = "answer"
    }
}
