// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.closeReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The /phone contract (server/bridge/phone.py):
 * - {"type":"ready"} is the first message; nothing binary goes out before it;
 * - the server sends binary frames only, at most 64 KiB each, each holding whole messages; text from it is a protocol
 *   error (1003);
 * - one {"type":"written"} after each of the server's frames has been fully written to the Flipper, and only for those:
 *   the relay's own frames (the remote's keys) are never acknowledged;
 * - Flipper bytes go to the server as they come, unchanged and in order, the replies to the relay's own ids included
 *   (the server drops those by id);
 * - `command_id` ranges (docs/protocol/rpc/): `1 … 2^28 − 1` the server's requests, `2^28 … 2^29 − 1` the server's settle
 *   stop, `2^29 … 2^30 − 1` the relay's own messages (it counts from `2^29` and wraps inside its range), `2^30 …`
 *   reserved, `0` the Flipper's unsolicited messages. The relay never sends id 0 and never writes the serial service's
 *   reset characteristic.
 */
object PhoneProtocol {
    const val MAX_FRAME = 65536
    const val MAX_QUEUED = 64
    const val OUTBOX_LIMIT = 1L shl 20
    const val WRITTEN = """{"type":"written"}"""

    fun ready(appVersion: String, device: String?, mtu: Int?): String = buildJsonObject {
        put("type", "ready")
        put("protocol", 1)
        put("transport", "BLE")
        put("app", appVersion)
        if (device != null) put("device", device)
        if (mtu != null) put("mtu", mtu)
    }.toString()
}

/** Why a session ended. [code] and [wire] are what the relay sends when it closes the socket itself. */
sealed class SessionEnd(val code: Int = 1011) {
    data class ServerClosed(val serverCode: Int, val reason: String) : SessionEnd()
    data class NetworkLost(val error: NetError) : SessionEnd()
    data object ServerText : SessionEnd(1003)
    data class TooBig(val size: Int) : SessionEnd(1009)
    data object QueueFull : SessionEnd(1008)
    data object ServerNotReading : SessionEnd(1008)
    data class FlipperLost(val error: FlipperException) : SessionEnd()
    data object Locked : SessionEnd()
    data object Stopped : SessionEnd(1000)

    /** The relay closes the socket for every end except the ones where the server or the network did. */
    val closeBySelf get() = this !is ServerClosed && this !is NetworkLost

    /** The close reason for the server, which stores it: always English. */
    val wire: String get() = RelayStrings.En.sessionEnd(this)
}

class RelaySession(
    private val socket: PhoneSocket,
    private val link: FlipperLink,
    private val appVersion: String,
    private val onTraffic: (fromFlipper: Long, toFlipper: Long) -> Unit = { _, _ -> },
    /** When set, one log line per frame from the server says how long BLE took and when the Flipper answered. */
    private val timing: RelayLog? = null,
    private val clock: Clock = Clock.System,
    private val strings: () -> RelayStrings = { RelayStrings.En },
) {
    private var remote: RelayRemote? = null
    private var log: RelayLog? = null

    /** The remote's side: the session reads the Flipper's stream for it and writes its keys; [log] gets its lines. */
    fun remote(remote: RelayRemote, log: RelayLog?): RelaySession {
        this.remote = remote
        this.log = log
        return this
    }

    private var pending: Timing? = null
    private var flush: kotlinx.coroutines.Job? = null

    /**
     * One frame written to the Flipper and the bytes that came back after it, relative to the write's end. Small
     * chunks (RPC replies) are listed one by one; larger ones (screen frames, file data) are only counted, so they
     * never push a reply out of the line.
     */
    private class Timing(val bytes: Int, val writeMs: Long, val writtenAt: Long) {
        val replies = mutableListOf<Pair<Long, Int>>()
        var more = false
        var largeChunks = 0
        var largeBytes = 0
    }

    /** Runs until the session ends; cancelling it closes the socket as "relay stopped". */
    suspend fun run(): SessionEnd = coroutineScope {
        val end = CompletableDeferred<SessionEnd>()
        socket.sendText(PhoneProtocol.ready(appVersion, link.name, link.mtu))
        val writes = Channel<ByteArray>(PhoneProtocol.MAX_QUEUED)
        val side = remote?.let { RemoteSession(it, log, strings, clock) }
        val jobs = listOf(
            launch { readServer(writes, end) },
            launch { writeFlipper(writes, end, this, side) },
            launch { readFlipper(end, side) },
            launch { end.complete(SessionEnd.FlipperLost(link.ended.await())) },
            launch {
                link.rpcOpen.first { !it }
                end.complete(SessionEnd.Locked)
            },
        )
        val result = try {
            end.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            socket.close(SessionEnd.Stopped.code, closeReason(SessionEnd.Stopped.wire))
            throw e
        } finally {
            jobs.forEach { it.cancel() }
            writes.close()
            side?.end()
        }
        if (result.closeBySelf) socket.close(result.code, closeReason(result.wire)) else socket.cancel()
        result
    }

    private suspend fun readServer(writes: Channel<ByteArray>, end: CompletableDeferred<SessionEnd>) {
        for (event in socket.events) {
            when (event) {
                is SocketEvent.Binary -> {
                    if (event.bytes.size > PhoneProtocol.MAX_FRAME) end.complete(SessionEnd.TooBig(event.bytes.size))
                    else if (!writes.trySend(event.bytes).isSuccess) end.complete(SessionEnd.QueueFull)
                }
                is SocketEvent.Text -> end.complete(SessionEnd.ServerText)
                is SocketEvent.Closed -> end.complete(SessionEnd.ServerClosed(event.code, event.reason))
                is SocketEvent.Failure -> end.complete(SessionEnd.NetworkLost(event.error))
            }
            if (end.isCompleted) return
        }
        end.complete(SessionEnd.NetworkLost(NetError.Closed))
    }

    /**
     * The one writer to the Flipper: the server's frames first, in order, each followed by its `written`; a key of the
     * remote only when no server frame waits and [RemoteSession] lets it go, and without `written`.
     */
    private suspend fun writeFlipper(
        writes: Channel<ByteArray>,
        end: CompletableDeferred<SessionEnd>,
        scope: CoroutineScope,
        side: RemoteSession?,
    ) {
        try {
            while (true) {
                when (val out = next(writes, side) ?: return) {
                    is Out.Server -> writeServer(out.bytes, scope, side)
                    is Out.Key -> writeKey(out.key, scope, side!!)
                    Out.Again -> Unit
                }
            }
        } catch (e: FlipperException) {
            end.complete(if (!link.rpcOpen.value) SessionEnd.Locked else SessionEnd.FlipperLost(e))
        }
    }

    private suspend fun writeServer(frame: ByteArray, scope: CoroutineScope, side: RemoteSession?) {
        side?.serverFrame(frame)
        val started = clock.elapsed()
        link.write(frame)
        onTraffic(0, frame.size.toLong())
        socket.sendText(PhoneProtocol.WRITTEN)
        if (timing != null) {
            flushTiming()
            val now = clock.elapsed()
            pending = Timing(frame.size, now - started, now)
            flush = scope.launch { delay(TIMING_QUIET_MS); flushTiming() }
        }
    }

    /** A key of the remote: never acknowledged with `written`, which is one per server frame. */
    private suspend fun writeKey(key: RemoteSession.KeyFrame, scope: CoroutineScope, side: RemoteSession) {
        link.write(key.bytes)
        onTraffic(0, key.bytes.size.toLong())
        scope.launch { delay(RemoteSession.KEY_REPLY_MS); side.keyTimeout(key) }
    }

    private sealed interface Out {
        class Server(val bytes: ByteArray) : Out
        class Key(val key: RemoteSession.KeyFrame) : Out
        /** Something changed: look again. */
        data object Again : Out
    }

    /** The next thing to write, or null once the server's channel is closed. The server's frames always come first. */
    private suspend fun next(writes: Channel<ByteArray>, side: RemoteSession?): Out? {
        val now = writes.tryReceive()
        if (now.isSuccess || now.isClosed) return now.getOrNull()?.let { Out.Server(it) }
        val keys = side?.takeIf { it.keysFree }
        return select {
            writes.onReceiveCatching { r -> r.getOrNull()?.let { Out.Server(it) } }
            if (keys != null) remote!!.queue.onReceive { press -> keys.takeKey(press)?.let { Out.Key(it) } ?: Out.Again }
            if (side != null) side.wake.onReceive { Out.Again }
        }
    }

    private suspend fun readFlipper(end: CompletableDeferred<SessionEnd>, side: RemoteSession?) {
        for (bytes in link.received) {
            socket.sendBinary(bytes)
            onTraffic(bytes.size.toLong(), 0)
            // The server has its bytes already; the remote reads a copy, and nothing it does reaches them.
            if (side != null) {
                try {
                    side.flipperChunk(bytes.copyOf())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Unit
                }
            }
            pending?.let { t ->
                when {
                    bytes.size > SMALL_CHUNK -> { t.largeChunks++; t.largeBytes += bytes.size }
                    t.replies.size < TIMING_REPLIES -> t.replies += (clock.elapsed() - t.writtenAt) to bytes.size
                    else -> t.more = true
                }
            }
            if (socket.queueSize() > PhoneProtocol.OUTBOX_LIMIT) {
                end.complete(SessionEnd.ServerNotReading)
                return
            }
        }
    }

    private fun flushTiming() {
        val t = pending ?: return
        pending = null
        flush?.cancel()
        timing?.info(strings().logRpcTiming(t.bytes, t.writeMs, t.replies.toList(), t.more, t.largeChunks, t.largeBytes))
    }

    private companion object {
        const val TIMING_REPLIES = 8
        const val SMALL_CHUNK = 64
        const val TIMING_QUIET_MS = 2_500L
    }
}

/** Calls [tick] every [periodMs] while [block] runs; used for the per-minute traffic summary. */
internal suspend fun <T> withTicker(periodMs: Long, tick: () -> Unit, block: suspend CoroutineScope.() -> T): T =
    coroutineScope {
        val ticker = launch {
            while (true) {
                delay(periodMs)
                tick()
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
            tick()
        }
    }

