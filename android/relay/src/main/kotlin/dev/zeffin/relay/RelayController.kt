// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import dev.zeffin.core.Addresses
import dev.zeffin.core.CoreGraph
import dev.zeffin.core.FlipperChoice
import dev.zeffin.core.Lang
import dev.zeffin.relay.ble.AndroidBleRadio
import dev.zeffin.relay.ble.BleFlipperConnector
import okhttp3.HttpUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The relay for the whole process: its state, its log, and start/stop. The app calls start()
 * and stop(); the service calls runEngine(). One engine at a time.
 */
class RelayController private constructor(private val context: Context) {
    val runtime = RelayRuntime()
    val log = RelayLog(File(context.filesDir, "relay-log.tsv"))
    private val radio = AndroidBleRadio(context)
    private val core = CoreGraph.get(context)
    private val skipMtu = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The engine while the service runs it; null once it has ended (stopAndJoin waits for that). */
    internal val engineNow = MutableStateFlow<RelayEngine?>(null)
    private val engine: RelayEngine? get() = engineNow.value

    /** The remote: the frames the relay reads on its link, whether a key can go, and the keys pressed. */
    val remote = RelayRemote()
    /** The last screen frame the relay read: raw bytes and orientation, decoded by the app. Keys go by remote.press. */
    val frames get() = remote.frames

    /** The app's version, sent to the server in ready. */
    var appVersion: String = "0"
    /** The test app logs each RPC frame's BLE timing; the released app does not (the log would fill with them). */
    var rpcTiming: Boolean = false

    private val _lang = MutableStateFlow(currentLang(context))
    /** The language the relay speaks in: the app's own choice, else the phone's. */
    val lang: StateFlow<Lang> = _lang.asStateFlow()
    val strings: () -> RelayStrings = { RelayStrings.of(lang.value) }

    /** Call when the language may have changed: the notification and new log lines follow it. */
    fun refreshLang() {
        _lang.value = currentLang(context)
        RelayService.ensureChannel(context, strings())
    }

    init {
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = refreshLang()
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        // Changing the pairing or the Flipper restarts the cycle at once. The address and the Phone token come from one
        // snapshot of the pairing record, never from two stores.
        scope.launch {
            combine(core.settings.settings.map { it.flipper }, core.pairing.pairing.map { it?.base to it?.phoneToken }, ::Pair)
                .distinctUntilChanged()
                .drop(1)
                .collect { engine?.retryNow() }
        }
    }

    fun start() {
        skipMtu.set(false)
        log.info(strings().logStartedByUser)
        context.startForegroundService(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START))
    }

    fun stop() {
        context.startService(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_STOP))
    }

    fun nudge() = engine?.nudge() ?: Unit
    fun retryNow() = engine?.retryNow() ?: Unit

    /** The phone's Bluetooth right now. */
    fun radioState(): RadioState = radio.state()

    /** The devices paired with the phone, the likely Flippers first; empty while Bluetooth cannot be used. */
    fun bondedDevices(): List<BondedDevice> = radio.bonded()

    /** Tries a server address and Phone token the way the relay will, without saving them. */
    suspend fun checkServer(base: HttpUrl, token: String): CheckResult = OkHttpServerChecker(Addresses.checkUrl(base), token, core.http).check()

    internal suspend fun runEngine(service: RelayService) {
        val e = RelayEngine(::loadConfig, backends, runtime, log, appVersion, strings = strings, rpcTiming = rpcTiming)
        e.remote = remote
        engineNow.value = e
        try {
            e.run()
        } finally {
            engineNow.compareAndSet(e, null)
        }
    }

    private suspend fun loadConfig(): ConfigState {
        val pairing = core.pairing.current()  // one snapshot: the address with its own Phone token
        val chosen = core.settings.current().flipper  // the remote can use it even without a server
        val base = pairing?.base ?: return ConfigState.Missing(Problem.NOT_CONFIGURED, chosen)
        val token = pairing.phoneToken ?: return ConfigState.Missing(Problem.NOT_CONFIGURED, chosen)
        if (pairing.needsPairing) return ConfigState.Missing(Problem.NEEDS_PAIRING, chosen)  // nothing is sent without the server's key
        val flipper = chosen ?: return ConfigState.Missing(Problem.NOT_CHOSEN)
        return ConfigState.Ready(RelayConfig(base, token, flipper))
    }

    private val backends = object : RelayBackends {
        override fun checker(config: RelayConfig) = OkHttpServerChecker(Addresses.checkUrl(config.base), config.phoneToken, core.http)
        override fun dialer(config: RelayConfig) = OkHttpPhoneDialer(Addresses.phoneUrl(config.base), config.phoneToken, core.http)
        override fun connector(flipper: FlipperChoice) =
            BleFlipperConnector(radio, flipper.address, flipper.name, log, skipMtu, strings = strings)
    }

    companion object {
        /** The extra of an intent that opens the app on a screen, and its value for the relay's screen. */
        const val EXTRA_OPEN = "open"
        const val OPEN_RELAY = "relay"

        @Volatile private var instance: RelayController? = null

        fun currentLang(context: Context): Lang {
            val locales = context.getSystemService(LocaleManager::class.java)
            val chosen = locales.applicationLocales
            val list: LocaleList = if (!chosen.isEmpty) chosen else locales.systemLocales
            return Lang.pick((0 until list.size()).map { list[it].toLanguageTag() })
        }

        fun get(context: Context): RelayController = instance ?: synchronized(this) {
            instance ?: RelayController(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Stops the relay and waits until its engine has ended (its server calls cancelled), at most [timeoutMs]: before a new
 * pairing is written. True when it was running, so the caller starts it again after.
 */
suspend fun RelayController.stopAndJoin(timeoutMs: Long = 10_000): Boolean {
    if (engineNow.value == null && !runtime.state.value.running) return false
    stop()
    withTimeoutOrNull(timeoutMs) { engineNow.first { it == null } }
    return true
}
