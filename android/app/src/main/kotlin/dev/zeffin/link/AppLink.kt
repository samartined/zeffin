// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import dev.zeffin.core.ServerHttp
import dev.zeffin.core.cleanText
import dev.zeffin.link.AppProtocol.In
import dev.zeffin.link.AppProtocol.Out
import dev.zeffin.relay.NetError
import dev.zeffin.relay.describeNetworkError
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64

/**
 * Where /app is. [KEY_CHANGED]: the server showed another key than the pairing's; [NEEDS_PAIRING]: the pairing has no
 * key (from before the server's key was pinned). Both sent nothing and wait for a new pairing, like [REJECTED].
 */
enum class Conn { NOT_CONFIGURED, CONNECTING, CONNECTED, REJECTED, KEY_CHANGED, NEEDS_PAIRING, OFFLINE, STOPPED }

/** The link waits for a new pairing (or Retry): a refused token, a changed key, or no key. */
val Conn.waitsForPairing get() = this == Conn.REJECTED || this == Conn.KEY_CHANGED || this == Conn.NEEDS_PAIRING

data class RelayInfo(val app: String?, val device: String?, val mtu: Int?)

/** Hub.status() of the server, as /app sends it. */
data class ServerStatus(
    val connected: Boolean = false,
    val phoneAttached: Boolean = false,
    val transport: String? = null,
    val relay: RelayInfo? = null,
    val gate: String = "active",
    val gateChangedAt: Double? = null,
    val gateBy: String? = null,
    val screenSeq: Long = 0,
    val screenStale: Boolean = false,
    val screenAgeS: Double? = null,
    val sessionUptimeS: Double? = null,
    val lastPingMs: Double? = null,
    val sessionFrames: Long? = null,
    val sessionBytesIn: Long? = null,
    val sessionBytesOut: Long? = null,
    /** A Flipper call holds the Flipper (the server's ops_lock): the relay's keys wait behind it. */
    val busy: Boolean = false,
)

/** One agent action from the server's audit log. Everything in it is shown as plain text. */
data class AuditItem(
    val ts: Double, val tool: String, val args: String?, val result: String?, val ms: Long?,
    val seqAfter: Long?, val action: String?, val by: String?,
) {
    val isOperator get() = tool == "operator"
    val failed get() = result != null && result != "ok"
}

/** Figures of a relay event; the screens word them. [lead] is language-neutral (transport, Flipper, MTU, status). */
data class PhoneDetail(val lead: String?, val firstFrameS: Double?, val frames: Long?, val code: Int?, val reason: String?)

data class PhoneEvent(val ts: Double, val ev: String, val reason: String?, val detail: PhoneDetail?)

/**
 * One screen from the Flipper; [receivedAt]: when it reached the app (epoch ms), null for frames not from the link.
 * [fromRelay]: read by this phone's relay on its own link, not sent by the server on `/app`.
 */
data class Frame(val seq: Long, val orientation: Int, val stale: Boolean, val data: ByteArray, val receivedAt: Long? = null,
                 val fromRelay: Boolean = false)

data class AppLinkState(
    val conn: Conn = Conn.NOT_CONFIGURED,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val status: ServerStatus? = null,
    val audit: List<AuditItem> = emptyList(),
    val phone: List<PhoneEvent> = emptyList(),
    val diag: JsonObject? = null,
    val latencyMs: Long? = null,
    val error: NetError? = null,
    /** What the server offers (hello.features): "chat", "attachments", "delete", "remote". */
    val features: Set<String> = emptySet(),
    /** hello's "protocol": the /app version the server speaks (AppProtocol.PROTOCOL is this app's). Kept, not shown yet. */
    val protocol: Int? = null,
)

/** What the link hands on, in the order it happened: the chat rides on this socket. */
sealed interface LinkEvent {
    /** After each connection: what the server offers, and the whole [hello] (its agents, each with what it offers). */
    data class Hello(val features: Set<String>, val hello: JsonObject? = null) : LinkEvent
    /** Every chat.* message, and the errors that answer a chat.* request. */
    data class Chat(val message: JsonObject) : LinkEvent
    /** A server error that is not the chat's: it goes to the app's log, never to a banner. */
    data class OtherError(val message: JsonObject) : LinkEvent
    /** The connection was lost or closed. */
    data object Disconnected : LinkEvent
    /** The operator resumed after a stop, so the relay retries at once. */
    data object Resumed : LinkEvent
}

/**
 * The app's channel to the server (/app): status, live screen, agent actions, relay events,
 * diagnostics, and the operator's gate. Open only while the app is in view.
 * [pingEveryMs] and the backoff are fixed in the app; tests make them short.
 */
class AppLink(
    private val http: ServerHttp,
    private val pingEveryMs: Long = 10_000L,
    private val firstWaitMs: Long = 1_000L,
    private val maxWaitMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AppLinkState())
    val state: StateFlow<AppLinkState> = _state.asStateFlow()

    /** Separate from the state: up to 10 a second, only the screen redraws. */
    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    /**
     * Everything that is not state, in order. Nothing is kept for a late subscriber: subscribe before [start], or the
     * first hello is lost. The buffer has no limit, so the socket's thread never waits for the collector.
     */
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = Int.MAX_VALUE)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    private var target: Pair<String, String>? = null
    /** Bumped by every start and stop: a socket's late callbacks from an older generation change nothing. */
    @Volatile private var generation = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastPong = 0L

    /** Starts (or restarts with a new address/token) the connection loop. */
    @Synchronized
    fun start(url: String?, token: String?) {
        val t = if (url != null && token != null) url to token else null
        if (t == null) {
            stop()
            _state.update { AppLinkState(conn = Conn.NOT_CONFIGURED) }
            return
        }
        if (job?.isActive == true && t == target) return
        stop()
        target = t
        val gen = generation
        job = scope.launch { loop(t.first, t.second, gen) }
    }

    /**
     * Ends the connection at once: the socket is cancelled, not closed, so a request in flight is aborted and nothing
     * more goes out with the old token. Returns the loop's job, for [stopAndJoin].
     */
    @Synchronized
    fun stop(): Job? {
        generation++
        val old = job
        job?.cancel()
        job = null
        target = null
        socket?.cancel()
        socket = null
        _state.update { it.copy(conn = if (it.conn.waitsForPairing) it.conn else Conn.STOPPED) }
        return old
    }

    /** [stop], then waits for the old loop to end: before a new pairing is written. */
    suspend fun stopAndJoin() {
        stop()?.join()
    }

    /** Try again now (after a token change, say). */
    fun retry() {
        wake.trySend(Unit)
    }

    /** The private network's tunnel came back or changed: an offline link tries now; a rejected token or key still waits. */
    fun networkChanged() {
        if (!_state.value.conn.waitsForPairing) wake.trySend(Unit)
    }

    /** Pause, resume or stop the agent; [via] is [AppProtocol.GateVia]'s, recorded as who changed it (the remote). */
    fun gate(action: String, via: String? = null) = send(gateMessage(action, via).toString())
    fun screenFresh() = send("""{"type":"${In.SCREEN_FRESH}"}""")
    fun requestDiag() = send("""{"type":"${In.DIAG}"}""")
    fun sendJson(message: JsonObject) = send(message.toString())
    private fun send(text: String) = socket?.send(text) == true

    private fun live(gen: Long) = gen == generation

    /** Changes the state only while [gen] is the current generation; false once it has been stopped. */
    private fun update(gen: Long, change: (AppLinkState) -> AppLinkState): Boolean = synchronized(this) {
        live(gen).also { if (it) _state.update(change) }
    }

    private suspend fun loop(url: String, token: String, gen: Long) {
        var wait: Long? = firstWaitMs  // null once the link was stopped
        while (wait != null && scope.isActive && update(gen) { it.copy(conn = Conn.CONNECTING) }) {
            val now: Long = wait
            wait = connectOnce(url, token, gen)?.let { (code, error) ->
                _events.tryEmit(LinkEvent.Disconnected)
                waitAfter(code, error, now, gen)
            }
        }
    }

    /** After a connection ends: waits as its end asks and returns the next wait, or null once the link was stopped. */
    private suspend fun waitAfter(code: Int?, error: NetError?, wait: Long, gen: Long): Long? = when {
        code == 401 -> if (update(gen) { it.copy(conn = Conn.REJECTED, error = null) }) {
            wake.receive() // only a new token (retry) tries again
            firstWaitMs
        } else null
        // Nothing was sent: a hard stop, not a backoff. Only a new pairing (a new start) or Retry tries again.
        error == NetError.KeyChanged || error == NetError.NoKey ->
            if (update(gen) { it.copy(conn = if (error == NetError.KeyChanged) Conn.KEY_CHANGED else Conn.NEEDS_PAIRING, error = error) }) {
                wake.receive()
                firstWaitMs
            } else null
        else -> {
            val w = if (_state.value.conn == Conn.CONNECTED) firstWaitMs else wait
            if (update(gen) { it.copy(conn = Conn.OFFLINE, error = error) }) {
                withTimeoutOrNull(w) { wake.receive() }
                (w * 2).coerceAtMost(maxWaitMs)
            } else null
        }
    }

    /** One connection until it ends: its HTTP code (a refusal) and error; null when the link was stopped meanwhile. */
    private suspend fun connectOnce(url: String, token: String, gen: Long): Pair<Int?, NetError?>? {
        val closed = Channel<Pair<Int?, NetError?>>(1)
        val ws = http.newWebSocket(ServerHttp.Use.APP_SOCKET, Request.Builder().url(url).header("Authorization", "Bearer $token").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!update(gen) { it.copy(conn = Conn.CONNECTED, error = null) }) webSocket.cancel()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (live(gen)) handle(text)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed.trySend(null to NetError.Eof)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.trySend(null to null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.trySend(response?.code to describeNetworkError(t))
                    response?.close()
                }
            })
        if (!swapSocket(gen, ws)) return null
        lastPong = 0L
        val pinger = scope.launch { ping(ws) }
        val end = try {
            closed.receive()
        } finally {
            pinger.cancel()
        }
        // Stopped meanwhile: the state and the socket are the new generation's.
        return end.takeIf { swapSocket(gen, null) }
    }

    /** Sets the current socket while [gen] is current; otherwise cancels [ws] (a stale one) and says so. */
    private fun swapSocket(gen: Long, ws: WebSocket?): Boolean = synchronized(this) {
        live(gen).also { if (it) socket = ws else ws?.cancel() }
    }

    /** A ping at once and every [pingEveryMs]: measures the latency, and reconnects if a pong does not come. */
    private suspend fun ping(ws: WebSocket) {
        while (true) {
            pingSentAt = System.nanoTime()
            if (!ws.send("""{"type":"${In.PING}"}""")) return
            delay(pingEveryMs)
            if (lastPong < pingSentAt) {
                ws.cancel()
                return
            }
        }
    }

    @Volatile private var pingSentAt = 0L

    internal fun handle(text: String) {
        val m = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return
        }
        when (m.s("type")) {
            Out.HELLO -> {
                val server = m["server"] as? JsonObject
                val features = m.strings("features", 32).toSet()
                _state.update {
                    it.copy(serverName = server?.s("name"), serverVersion = server?.s("version"), protocol = m.i("protocol"),
                        features = features)
                }
                _events.tryEmit(LinkEvent.Hello(features, m))
            }
            Out.STATUS -> {
                val status = parseStatus(m)
                val before = _state.value.status?.gate
                _state.update { it.copy(status = status) }
                if (before != null && before != "active" && status.gate == "active") _events.tryEmit(LinkEvent.Resumed)
            }
            Out.FRAME -> {
                // Raw base64, not s(): that one cleans and shortens text for display.
                val text = (m["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
                val data = runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { it.size == FRAME_BYTES } ?: return
                _frame.value = Frame(m.l("seq") ?: 0, m.i("orientation") ?: 0, m.b("stale") ?: false, data, receivedAt = clock())
            }
            Out.AUDIT_HISTORY -> _state.update { it.copy(audit = items(m).mapNotNull(::parseAudit).takeLast(MAX_AUDIT)) }
            Out.AUDIT -> (m["item"] as? JsonObject)?.let(::parseAudit)?.let { a ->
                _state.update { it.copy(audit = (it.audit + a).takeLast(MAX_AUDIT)) }
            }
            Out.PHONE_HISTORY -> _state.update { it.copy(phone = items(m).mapNotNull(::parsePhone).takeLast(MAX_PHONE)) }
            Out.PHONE -> (m["item"] as? JsonObject)?.let(::parsePhone)?.let { p ->
                _state.update { it.copy(phone = (it.phone + p).takeLast(MAX_PHONE)) }
            }
            Out.DIAG -> _state.update { it.copy(diag = m) }
            Out.PONG -> {
                lastPong = System.nanoTime()
                _state.update { it.copy(latencyMs = (lastPong - pingSentAt) / 1_000_000) }
            }
            // Only the chat's errors carry a chat.* request; the others (bad_json, unknown_type for diag on an older server…) are not the chat's.
            Out.ERROR -> _events.tryEmit(if (m.s("request")?.startsWith(AppProtocol.CHAT_PREFIX) == true) LinkEvent.Chat(m) else LinkEvent.OtherError(m))
            else -> if (m.s("type")?.startsWith(AppProtocol.CHAT_PREFIX) == true) _events.tryEmit(LinkEvent.Chat(m))
        }
    }

    companion object {
        const val MAX_AUDIT = 100
        const val MAX_PHONE = 50

        private fun items(m: JsonObject) = m.objects("items")

        /** The gate message: pause, resume or stop, with the remote's `via` when it is the remote's (gate.json). */
        fun gateMessage(action: String, via: String? = null): JsonObject = buildJsonObject {
            put("type", In.GATE)
            put("action", action)
            if (via != null) put("via", via)
        }

        fun parseStatus(m: JsonObject): ServerStatus {
            val relay = m["relay"] as? JsonObject
            val screen = m["screen"] as? JsonObject
            return ServerStatus(
                connected = m.b("connected") ?: false,
                phoneAttached = m.b("phone_attached") ?: false,
                transport = m.s("transport"),
                relay = relay?.let { RelayInfo(it.s("app"), it.s("device"), it.i("mtu")) },
                gate = m.s("gate") ?: "active",
                gateChangedAt = m.d("gate_changed_at"),
                gateBy = m.s("gate_by"),
                screenSeq = screen?.l("seq") ?: 0,
                screenStale = screen?.b("stale") ?: false,
                screenAgeS = screen?.d("age_s"),
                sessionUptimeS = m.d("session_uptime_s"),
                lastPingMs = m.d("last_ping_ms"),
                sessionFrames = m.l("session_frames"),
                sessionBytesIn = m.l("session_bytes_in"),
                sessionBytesOut = m.l("session_bytes_out"),
                busy = m.b("busy") ?: false,
            )
        }

        fun parseAudit(o: JsonObject): AuditItem? {
            val tool = o.s("tool") ?: return null
            return AuditItem(o.d("ts") ?: 0.0, tool, summarize(o["args"]), o.s("result", 120), o.l("ms"),
                o.l("seq_after"), o.s("action"), o.s("by"))
        }

        fun parsePhone(o: JsonObject): PhoneEvent? {
            val ev = o.s("ev") ?: return null
            val lead = listOfNotNull(o.s("transport"), o.s("device")?.let { "Flipper $it" }, o.i("mtu")?.let { "MTU $it" }, o.s("status"))
                .joinToString(" · ").ifEmpty { null }
            val detail = PhoneDetail(lead, o.d("after_ready_s"), o.l("frames"), o.i("phone_code"), o.s("phone_reason", 123))
                .takeIf { it.lead != null || it.firstFrameS != null || it.frames != null || it.code != null || it.reason != null }
            return PhoneEvent(o.d("ts") ?: 0.0, ev, o.s("reason", 120), detail)
        }

        /** Tool arguments as one line: key=value, …; cut to [limit] (by default no more than the screen can show). */
        fun summarize(e: JsonElement?, limit: Int = 160): String? = when (e) {
            null, JsonNull -> null
            is JsonObject -> e.entries.joinToString(", ") { (k, v) -> "$k=${summarize(v, limit) ?: ""}" }.let { cleanText(it, limit) }
            is JsonArray -> e.joinToString(", ") { summarize(it, limit) ?: "" }.let { cleanText(it, limit) }
            is JsonPrimitive -> cleanText(e.content, limit)
        }

        /** The Flipper framebuffer: 128x64 pixels, 1 bit each. */
        const val FRAME_BYTES = 1024

    }
}
