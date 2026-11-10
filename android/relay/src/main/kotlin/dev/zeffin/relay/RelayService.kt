// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.zeffin.core.CoreGraph
import dev.zeffin.core.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service of type connectedDevice that runs the relay engine. It holds a partial
 * wake lock only while the engine asks for it (session running, first minutes of retries),
 * and nudges the engine when the network or the private network's tunnel comes back, Bluetooth turns on or a bond changes.
 */
/**
 * Follows the relay's state for the foreground notification: [show] runs when the state or the language changes, and
 * again when the text's clock moves (a session's uptime, the retry countdown). Nothing runs in between.
 */
internal suspend fun followNotification(
    states: Flow<RelayState>,
    langs: Flow<Lang>,
    now: () -> Long,
    show: (RelayState, RelayStrings, Long) -> Unit,
) = combine(states, langs, ::Pair).collectLatest { (s, lang) ->
    val strings = RelayStrings.of(lang)
    while (true) {
        val t = now()
        show(s, strings, t)
        delay(strings.untilTextChanges(s, t) ?: break)
    }
}

class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engineJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val controller get() = RelayController.get(this)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.nudge()
        }
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = controller.nudge()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        // A null intent is Android restarting us after killing the process: only if the owner left it on.
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engineJob == null) {
            engineJob = scope.launch { controller.runEngine(this@RelayService) }
            scope.launch { followState() }
            registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }, RECEIVER_NOT_EXPORTED)
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
            // The tunnel coming back (or changing) retries at once, also when the default network did not change.
            scope.launch { CoreGraph.get(this@RelayService).network.changes.collect { controller.nudge() } }
        }
        return START_STICKY
    }

    private fun startInForeground(): Boolean = try {
        ensureChannel(this, controller.strings())
        startForeground(NOTIFICATION_ID, notification(controller.runtime.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (restarted from the background with battery
        // optimisation on) or SecurityException (Bluetooth permission withdrawn).
        Log.w(TAG, "Cannot start in the foreground: ${e.javaClass.simpleName}")
        controller.log.error(controller.strings().logBackgroundStartRefused)
        false
    }

    private suspend fun followState() {
        val nm = getSystemService(NotificationManager::class.java)
        var lastText = ""
        followNotification(controller.runtime.state, controller.lang, System::currentTimeMillis) { s, strings, now ->
            setWakeLock(s.wakeLock)
            val text = strings.notification(s, now)
            if (text != lastText) {
                nm.notify(NOTIFICATION_ID, notification(s, strings, text))
                lastText = text
            }
        }
    }

    private fun setWakeLock(on: Boolean) {
        val held = wakeLock?.isHeld == true
        if (on && !held) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeffin:relay").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } else if (!on && held) {
            wakeLock?.release()
        }
    }

    private fun notification(
        s: RelayState,
        strings: RelayStrings = controller.strings(),
        text: String = strings.notification(s, System.currentTimeMillis()),
    ): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(RelayController.EXTRA_OPEN, RelayController.OPEN_RELAY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_relay)
            .setContentTitle(strings.notificationTitle)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (open != null) setContentIntent(PendingIntent.getActivity(this@RelayService, 0, open, PendingIntent.FLAG_IMMUTABLE)) }
            .addAction(Notification.Action.Builder(null, strings.stopAction, stop).build())
            .build()
    }

    private fun stopEverything() {
        engineJob?.cancel()
        engineJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engineJob?.cancel()
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RelayService"
        const val CHANNEL = "relay"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.zeffin.relay.START"
        const val ACTION_STOP = "dev.zeffin.relay.STOP"

        /** Creates the channel, or renames it after a language change (creating it again updates name and description). */
        fun ensureChannel(context: Context, strings: RelayStrings) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, strings.channelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = strings.channelDescription
                setShowBadge(false)
            })
        }
    }
}
