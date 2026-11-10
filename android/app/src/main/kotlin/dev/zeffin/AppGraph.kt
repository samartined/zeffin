// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import dev.zeffin.core.Addresses
import dev.zeffin.core.AppSettings
import dev.zeffin.core.CoreGraph
import dev.zeffin.core.PairingRecord
import dev.zeffin.link.AppLink
import dev.zeffin.link.ChatStore
import dev.zeffin.link.LinkEvent
import dev.zeffin.link.PairingPorts
import dev.zeffin.link.PairingSaver
import dev.zeffin.link.appTarget
import dev.zeffin.link.s
import dev.zeffin.notify.AndroidNotifications
import dev.zeffin.notify.LinkRelease
import dev.zeffin.notify.Notifier
import dev.zeffin.notify.NotifyPrefs
import dev.zeffin.notify.NotifyService
import dev.zeffin.notify.awaitLinkRelease
import dev.zeffin.attach.AttachTray
import dev.zeffin.attach.Thumbnails
import dev.zeffin.attach.Uploader
import dev.zeffin.relay.RelayController
import dev.zeffin.relay.stopAndJoin
import dev.zeffin.relay.RadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.zeffin.ui.present.Warnings

/** The app's pieces, created once in the Application. No DI framework: the app is small. */
class AppGraph(private val app: Application) {
    val core = CoreGraph.get(app)
    val relay = RelayController.get(app).also {
        it.appVersion = BuildConfig.VERSION_NAME
        it.rpcTiming = BuildConfig.BUILD_TYPE == "preview"  // Zeffin Test: RPC timing lines in the relay log
    }
    /** /app, attachment uploads, thumbnails and pairing all go through core's ServerHttp: only through the tunnel. */
    private val http = core.http
    val link = AppLink(http)
    val chat: ChatStore = ChatStore(link::sendJson, remember = { id -> scopeIo.launch { core.settings.setChatConversation(id) } })
    /** Notifications when a turn ends or the agent asks, while the app is off screen. */
    val notifications: AndroidNotifications = AndroidNotifications(app, { id -> chat.state.value.conversations.firstOrNull { it.id == id }?.title },
        agentName = { chat.state.value.agentName }, home = { chat.state.value.home })
    val notifier: Notifier = Notifier(notifications, answer = chat::answer, connected = { chat.state.value.connected })
    private val scopeIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private suspend fun server(): Pair<okhttp3.HttpUrl, String>? {
        val p = core.pairing.current() ?: return null  // one snapshot: the address with its own App token
        return p.base to (p.appToken ?: return null)
    }
    private val uploader = Uploader(http)
    /** Pairing by a QR code or its text: the server's key, the code, then the owner's confirmation on the server. */
    val pairExchange = dev.zeffin.link.PairExchange(http)

    /** Tells the server a waiting pairing request is given up; it outlives the screen that asked. */
    fun cancelPairing(qr: dev.zeffin.core.PairingQr, waiting: dev.zeffin.link.PairExchange.Waiting) {
        scopeIo.launch { pairExchange.cancel(qr, waiting) }
    }

    /** This phone's own address in the private network, for the pairing screen (the server shows it too). */
    fun ownAddress(): String? = core.network.ownAddress()
    /** The files attached to the next chat message. */
    val tray = AttachTray(scopeIo) { conversation, item, progress ->
        val (base, token) = server() ?: return@AttachTray Result.failure(java.io.IOException("not paired"))
        uploader.upload(base, token, conversation, item, progress)
    }
    val thumbnails = Thumbnails(http, ::server)
    val version: String = BuildConfig.VERSION_NAME
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The settings and the pairing as the screens read them: loaded once at start, then kept current. */
    val settings: StateFlow<AppSettings> = core.settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val pairing: StateFlow<PairingRecord?> = core.pairing.pairing.stateIn(scope, SharingStarted.Eagerly, null)
    /** Saves a new pairing: the link and the relay stop first and start again after (PairingSaver). */
    val pairingSaver = PairingSaver(object : PairingPorts {
        override suspend fun stopLink() = link.stopAndJoin()
        override suspend fun stopRelay() = relay.stopAndJoin()
        override suspend fun write(record: PairingRecord) = core.pairing.save(record)
        override suspend fun startLink() = withContext(Dispatchers.Main.immediate) { if (linkJob?.isActive == true) followPairing() }
        override fun startRelay() = relay.start()
    })
    private var linkJob: Job? = null
    private var stopJob: Job? = null
    private var lostJob: Job? = null

    /** Off screen with work, a server that stays away for 15 min ends the wait (Notifier.linkLost). */
    private fun watchLost() {
        if (lostJob?.isActive == true) return
        lostJob = scope.launch {
            delay(Notifier.LOST_AFTER_MS)
            notifier.linkLost()
        }
    }

    /** The link's events, on the main thread like the UI's commands, so the chat and the notifier never lock. */
    private fun onLinkEvent(e: LinkEvent) {
        when (e) {
            is LinkEvent.Hello -> { chat.onHello(e.features, e.hello); lostJob?.cancel() }
            is LinkEvent.Chat -> { chat.onMessage(e.message); notifier.onMessage(e.message) }
            LinkEvent.Disconnected -> { chat.onDisconnected(); watchLost() }
            is LinkEvent.OtherError -> Log.w("AppLink", "server error ${e.message.s("error")} for ${e.message.s("request") ?: "no request"}")
            LinkEvent.Resumed -> relay.nudge()
        }
    }

    init {
        // Subscribed at once, before foreground() can start the link: the events keep nothing for a late subscriber.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.events.collect(::onLinkEvent) }
        // The saved conversation is read off the main thread and handed to the chat on it.
        scope.launch { chat.restore(withContext(Dispatchers.IO) { core.settings.current().chatConversation }) }
        notifications.ensureChannels()
        scope.launch { core.settings.settings.collect { notifier.setPrefs(NotifyPrefs(it.notifyTurns, it.notifyPermissions)) } }
        // The private network's tunnel came back or changed: /app tries again at once (the relay's service does the same).
        scope.launch { core.network.changes.collect { link.networkChanged() } }
        // Something to listen for while off screen: keep the app alive (the first start is in background()).
        scope.launch { notifier.work.collect { if (it != null) NotifyService.start(app) } }
    }

    /**
     * /app follows the pairing record: its address and App token come from one snapshot, never from two stores.
     * Started again after a save, which stopped the link even when the record did not change.
     */
    private fun followPairing() {
        linkJob?.cancel()
        linkJob = scope.launch {
            core.pairing.pairing.collect { p -> appTarget(p).let { (url, token) -> link.start(url, token) } }
        }
    }

    /** The app came into view: open /app (following the settings) and start the relay if asked to. */
    fun foreground() {
        stopJob?.cancel()
        notifier.setVisible(true)
        if (linkJob?.isActive != true) followPairing()
        scope.launch {
            val s = core.settings.current()
            val configured = s.flipper != null && core.pairing.current()?.phoneToken != null
            val canStart = configured && relay.radioState() == RadioState.ON
            if (s.autoStart && canStart && !relay.runtime.state.value.running) {
                relay.start()
            }
        }
    }

    /**
     * The app left the screen: close /app 30 s later, unless it comes back; while something runs that notifies, once
     * it ends, or as soon as no service holds the app (Android refused it, so nothing keeps the socket alive).
     */
    fun background() {
        stopJob?.cancel()
        notifier.setVisible(false)
        stopJob = scope.launch {
            if (awaitLinkRelease(notifier.work, NotifyService.running) == LinkRelease.NO_SERVICE) {
                Log.w("AppGraph", "closing /app off screen: the agent works but the notification service is not running")
            }
            linkJob?.cancel()
            linkJob = null
            link.stop()
        }
    }

    fun warnings(context: Context): Warnings {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return Warnings(
            bluetoothPermission = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
            notifications = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            batteryOptimized = !power.isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted = activity.isBackgroundRestricted,
        )
    }

    suspend fun configured(): Boolean = core.pairing.pairing.first() != null
}

class ZeffinApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Once per process: /app is open only while the app is in view.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.foreground()
            override fun onStop(owner: LifecycleOwner) = graph.background()
        })
    }
}

val Context.graph: AppGraph get() = (applicationContext as ZeffinApp).graph
