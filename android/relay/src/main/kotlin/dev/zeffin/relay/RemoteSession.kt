// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.relay.rpc.Pb
import dev.zeffin.relay.rpc.RpcError
import dev.zeffin.relay.rpc.RpcIds
import dev.zeffin.relay.rpc.RpcMessage
import dev.zeffin.relay.rpc.RpcReader
import dev.zeffin.relay.rpc.SettleMatch
import kotlinx.coroutines.channels.Channel

/** Why the relay stopped reading the stream for this session. */
enum class ReaderStop { FLIPPER_STREAM, SERVER_FRAME }

/**
 * The remote's part of one [RelaySession]: it reads the Flipper's stream passively and decides when a key may go.
 * Everything here starts over with each session, so with each link and each RPC session of the Flipper (a lock ends the
 * server session): the reader, its sync, the server command still open and the key in flight.
 *
 * - **Reading:** the bytes are the server's first; this only reads a copy. It drops bytes until the reply to the server's
 *   settle stop (an outgoing `gui_stop_screen_stream_request` with an id in the settle range), found as the server finds
 *   it ([SettleMatch]); without it within [SETTLE_LIMIT_MS] it starts at the next chunk with an empty buffer. Then it reads
 *   screen frames and the replies to the relay's own ids. A parse error stops it for the session: no frames, keys off.
 * - **Without a server session** ([RemoteLinkSession]) the relay syncs the same way on its own stop ([settleOn]), and no
 *   server frame ever comes.
 * - **Keys:** one in flight. A key goes only when no server frame waits (the writer's choice), no server command is open
 *   (the last message of the last server frame had `has_next`), the reader is in step, and the last key's `RELEASE` has
 *   been answered. Without that reply within [KEY_REPLY_MS] the waiting keys are dropped and the keys are off until it comes.
 *
 * Called from the session's writer and reader coroutines: every method holds the lock.
 */
internal class RemoteSession(
    private val remote: RelayRemote,
    private val log: RelayLog?,
    private val strings: () -> RelayStrings,
    private val clock: Clock,
) {
    private enum class Read { WAITING, SETTLING, SYNCED, STOPPED }

    /** One key written and not yet answered. */
    class InFlight(val press: KeyPress, val ids: LongArray) {
        val release: Long get() = ids[2]
    }

    /** A key ready to write: its bytes, already checked with the relay's own reader. */
    class KeyFrame(val inFlight: InFlight, val bytes: ByteArray)

    private val lock = Any()
    private var read = Read.WAITING
    private var reader = RpcReader()
    private var settle: SettleMatch? = null
    private var settleSince = 0L
    private var commandOpen = false
    private var inFlight: InFlight? = null
    private var late = false
    private var ended = false

    /** Wakes the writer when a key may go again. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        publish()
    }

    /** May the writer take a key now (it still waits for the server's frames first)? */
    val keysFree: Boolean get() = synchronized(lock) { keysState == RemoteKeys.Ready && inFlight == null }

    /** The writer took one of the server's frames and is about to write it. */
    fun serverFrame(bytes: ByteArray) = synchronized(lock) {
        if (read == Read.STOPPED) return
        val messages = try {
            RpcReader().let { r -> r.feed(bytes).also { if (r.pending != 0) throw RpcError("a server frame ends inside a message") } }
        } catch (_: RpcError) {
            stop(ReaderStop.SERVER_FRAME)
            return
        }
        for (m in messages) {
            if (m.content == Pb.GUI_STOP_SCREEN_STREAM_REQUEST && RpcIds.isSettle(m.id)) settleOn(m.id)  // the lock is reentrant
        }
        messages.lastOrNull()?.let { commandOpen = it.hasNext }
        publish()
    }

    /** The reader stopped on a parse error: the link's stream cannot be read. */
    val readerStopped: Boolean get() = synchronized(lock) { read == Read.STOPPED }

    /**
     * A stop the reader syncs on, about to be written: the server's settle stop, or, without a server session, the relay's
     * own stop with [id] from its range. Bytes are dropped until its whole reply.
     */
    fun settleOn(id: Long) = synchronized(lock) {
        if (read != Read.STOPPED) {
            read = Read.SETTLING
            settle = SettleMatch(id)
            settleSince = clock.elapsed()
            reader = RpcReader()
            remote.frame(null)
            publish()
        }
    }

    /** A chunk from the Flipper, already sent to the server. Never throws. */
    fun flipperChunk(bytes: ByteArray) = synchronized(lock) {
        try {
            val data: ByteArray? = when (read) {
                Read.WAITING, Read.STOPPED -> null
                Read.SETTLING -> settled(bytes)
                Read.SYNCED -> bytes
            }
            if (data != null) for (m in reader.feed(data)) message(m)
        } catch (_: RpcError) {
            stop(ReaderStop.FLIPPER_STREAM)
        }
    }

    /** While settling: the bytes after the stop's reply, or null (dropped). Past [SETTLE_LIMIT_MS] the next chunk is read. */
    private fun settled(bytes: ByteArray): ByteArray? {
        val rest = settle?.feed(bytes)
        val late = rest == null && clock.elapsed() - settleSince >= SETTLE_LIMIT_MS
        if (late) log?.warn(strings().logRemoteSettleLate)  // the next chunk starts with an empty buffer, as the server's reading does
        if (rest != null || late) {
            settle = null
            read = Read.SYNCED
            publish()
        }
        return rest
    }

    private fun message(m: RpcMessage) {
        m.frame?.let { remote.frame(it.data, it.orientation, clock.now()) }
        val k = inFlight
        if (k == null || !RpcIds.isRelay(m.id) || m.id !in k.ids) return
        if (m.status != Pb.OK && refusedFor !== k) {  // one note per key
            refusedFor = k
            val name = Pb.statusName(m.status)
            remote.refused(k.press.key, k.press.long, m.status, name)
            log?.warn(strings().logKeyRefused(k.press.key, k.press.long, name ?: m.status.toString()))
        }
        if (m.id == k.release) {
            inFlight = null
            late = false
            publish()
        }
    }

    private var refusedFor: InFlight? = null

    /**
     * The next key to write, or null: a key from before the keys last went off is dropped, and one whose bytes do not read
     * back as its three requests is never written (a malformed message would make the Flipper end its RPC session). From
     * here its replies are expected: the writer writes it at once.
     */
    fun takeKey(press: KeyPress): KeyFrame? = synchronized(lock) {
        if (!remote.current(press) || keysState != RemoteKeys.Ready || inFlight != null) return null
        val ids = LongArray(3) { remote.nextId() }
        val bytes = keyBytes(remote.encode, ids, press)
        if (bytes == null) {
            log?.error(strings().logKeyNotSent(press.key))
            return null
        }
        KeyFrame(InFlight(press, ids), bytes).also {
            inFlight = it.inFlight
            late = false
            publish()
        }
    }

    /** [KEY_REPLY_MS] after a key was written: without its `RELEASE`'s reply, the keys wait for the Flipper. */
    fun keyTimeout(key: KeyFrame) = synchronized(lock) {
        if (inFlight !== key.inFlight || late) return
        late = true
        log?.warn(strings().logKeyLate(key.inFlight.press.key))
        publish()
    }

    /** The session ended: the keys are off and no frame is current. */
    fun end() = synchronized(lock) {
        ended = true
        remote.frame(null)
        publish()
    }

    private fun stop(why: ReaderStop) {
        if (read == Read.STOPPED) return
        read = Read.STOPPED
        settle = null
        remote.frame(null)
        log?.warn(strings().logRemoteStopped(why))
        publish()
    }

    private val keysState: RemoteKeys get() = when {
        ended -> RemoteKeys.Off(KeysOff.NO_SESSION)
        read == Read.STOPPED -> RemoteKeys.Off(KeysOff.READER_STOPPED)
        read != Read.SYNCED -> RemoteKeys.Off(KeysOff.SYNCING)
        late -> RemoteKeys.Off(KeysOff.WAITING_FLIPPER)
        commandOpen -> RemoteKeys.Off(KeysOff.SERVER_COMMAND_OPEN)
        else -> RemoteKeys.Ready
    }

    private fun publish() {
        val dropped = remote.keys(keysState)
        if (dropped > 0) log?.warn(strings().logKeysDropped(dropped))
        wake.trySend(Unit)
    }

    companion object {
        /** As the server's own wait for the settle stop's reply (server/bridge/hub.py, SETTLE_LIMIT_S). */
        const val SETTLE_LIMIT_MS = 5_000L
        const val KEY_REPLY_MS = 1_000L
    }
}

/** `pb.gui.InputKey`. */
internal fun wire(key: FlipperKey): Int = when (key) {
    FlipperKey.UP -> 0
    FlipperKey.DOWN -> 1
    FlipperKey.RIGHT -> 2
    FlipperKey.LEFT -> 3
    FlipperKey.OK -> 4
    FlipperKey.BACK -> 5
}

/** A key's frame from [encode], or null when its bytes do not read back as `PRESS`, `SHORT|LONG`, `RELEASE` with [ids]. */
internal fun keyBytes(encode: (LongArray, Int, Boolean) -> ByteArray, ids: LongArray, press: KeyPress): ByteArray? {
    val bytes = encode(ids, wire(press.key), press.long)
    val reader = RpcReader()
    val back = try {
        reader.feed(bytes)
    } catch (_: RpcError) {
        emptyList()
    }
    val types = listOf(Pb.PRESS, if (press.long) Pb.LONG else Pb.SHORT, Pb.RELEASE)
    val ok = reader.pending == 0 && back.size == 3 && back.indices.all {
        val m = back[it]
        m.id == ids[it] && m.content == Pb.GUI_SEND_INPUT_EVENT_REQUEST && !m.hasNext && m.input == wire(press.key) to types[it]
    }
    return bytes.takeIf { ok }
}
