// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.notify

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.zeffin.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while the agent works with the app off screen, so /app stays open and the turn's end or a
 * permission can be notified. It lives exactly while [Notifier.work] is set and shows "The agent is working".
 * Type remoteMessaging: it carries the agent's messages from the server to the phone.
 */
class NotifyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The socket needs the CPU with the screen off; a turn ends, and the lock has a ceiling anyway.
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeffin:agent")
            .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = graph
        // startForegroundService needs startForeground within seconds, even when the work is already over.
        try {
            show(g.notifier.work.value ?: Work("", System.currentTimeMillis() / 1000.0))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "not allowed to run in the foreground")
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        if (job == null) job = scope.launch {
            g.notifier.work.collect { work ->
                if (work != null) show(work) else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun show(work: Work) = startForeground(AndroidNotifications.ID_WORKING, graph.notifications.working(work),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotifyService"
        private const val MAX_HOLD_MS = 60 * 60 * 1000L

        private val _running = MutableStateFlow(false)
        /** True while the service holds the app in the foreground; /app stays open off screen only then (B5). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Starts the service; Android allows it while the app leaves the screen, or with battery optimization off. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, NotifyService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start from the background")
            }
        }
    }
}
