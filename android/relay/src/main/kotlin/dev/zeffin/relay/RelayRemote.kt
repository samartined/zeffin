// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.relay.rpc.RpcEncoder
import dev.zeffin.relay.rpc.RpcIds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** The Flipper's six keys, as the remote names them. */
enum class FlipperKey { UP, DOWN, RIGHT, LEFT, OK, BACK }

/**
 * One screen frame the relay read from the link: the framebuffer's bytes and its `pb.gui.ScreenOrientation`, as they came
 * (untrusted). The app decodes and rotates them (`link/FlipperFrame.kt`); a frame that is not 1024 bytes draws nothing.
 * [seq] counts the frames the relay read in this process; [receivedAt] is when it read this one (wall clock, ms), so
 * Home words its number and age as it does for the server's frames.
 */
class RelayFrame(val data: ByteArray, val orientation: Int, val seq: Long = 0, val receivedAt: Long = 0)

/** Whether the relay can send a key now, and why not. The agent's gate is the app's to check, not the relay's. */
sealed interface RemoteKeys {
    data object Ready : RemoteKeys
    data class Off(val why: KeysOff) : RemoteKeys
}

enum class KeysOff {
    /** The relay holds no link for the remote: no server session, and the remote's own hold not started (or ended). */
    NO_SESSION,
    /** The relay waits for a message boundary in the Flipper's stream (the server's settle). */
    SYNCING,
    /** The relay could not read the stream: off until the next session. */
    READER_STOPPED,
    /** A key's last reply did not come within a second: the Flipper is busy with an earlier command. */
    WAITING_FLIPPER,
    /** A command from the server is still being written in parts: a key would interrupt it. */
    SERVER_COMMAND_OPEN,
}

/** The Flipper answered a key with a status other than OK. [statusName] is the protocol's fixed name, never device text. */
data class KeyRefusal(val key: FlipperKey, val long: Boolean, val status: Int, val statusName: String?, val serial: Long)

/** [epoch]: the keys' state when it was pressed; a key from before the keys went off is never sent. */
internal class KeyPress(val key: FlipperKey, val long: Boolean, val epoch: Long)

/**
 * The remote's side of the relay, for the whole process: the frames it reads, whether keys can go, and the keys pressed.
 * The session that holds the link reads and writes through it (`RemoteSession`); without one the keys are off.
 *
 * [open] and [close] say the remote's screen is on: without a server session the engine then holds the Flipper for the
 * remote alone (`RemoteLinkSession`) and does not dial `/phone` until it closes.
 */
class RelayRemote internal constructor(firstId: Long = RpcIds.RELAY_MIN) {
    private val _frames = MutableStateFlow<RelayFrame?>(null)
    private val _keys = MutableStateFlow<RemoteKeys>(RemoteKeys.Off(KeysOff.NO_SESSION))
    private val _refused = MutableStateFlow<KeyRefusal?>(null)
    private val _opened = MutableStateFlow(false)
    private var openCount = 0

    /** The last frame read on the link; null without a session or before the stream is read in step. */
    val frames: StateFlow<RelayFrame?> = _frames.asStateFlow()
    val keys: StateFlow<RemoteKeys> = _keys.asStateFlow()
    /** The last key the Flipper refused, for one line on the remote; never retried. */
    val refused: StateFlow<KeyRefusal?> = _refused.asStateFlow()
    /** True while the remote's screen is open. */
    val opened: StateFlow<Boolean> = _opened.asStateFlow()

    /** The remote's screen opened. Counted: a screen built again (a rotation) may open before the old one closes. */
    fun open() = synchronized(this) {
        openCount++
        _opened.value = true
    }

    /** The remote's screen closed. */
    fun close() = synchronized(this) {
        openCount = maxOf(0, openCount - 1)
        _opened.value = openCount > 0
    }

    /** Keys pressed while one is still in flight, at most [QUEUE]; the session takes them one at a time. */
    internal val queue = Channel<KeyPress>(QUEUE)
    private val nextId = AtomicLong(firstId)
    /** Builds a key's frame; tests replace it to check that a frame that does not read back is never written. */
    internal var encode: (LongArray, Int, Boolean) -> ByteArray = RpcEncoder::keyFrame
    private val epoch = AtomicLong(0)
    private val refusals = AtomicLong(0)
    private val frameSeq = AtomicLong(0)

    /**
     * Queues a key: a short press, or a long one. False when the keys are off or [QUEUE] keys already wait; nothing is
     * sent then. The relay never sends `REPEAT` and never retries a key.
     */
    fun press(key: FlipperKey, long: Boolean): Boolean {
        val at = epoch.get()  // read before the state: keys(off) sets the state first, then moves the epoch
        if (_keys.value != RemoteKeys.Ready) return false
        return queue.trySend(KeyPress(key, long, at)).isSuccess
    }

    /** False for a key pressed before the keys last went off: it is dropped, never sent late. */
    internal fun current(press: KeyPress) = press.epoch == epoch.get()

    /** The next id of the relay's range, wrapping inside it. */
    internal fun nextId(): Long = nextId.getAndUpdate { if (it >= RpcIds.RELAY_MAX) RpcIds.RELAY_MIN else it + 1 }

    internal fun frame(frame: RelayFrame?) {
        _frames.value = frame
    }

    /** A frame read on the link, numbered and stamped with [at]. */
    internal fun frame(data: ByteArray, orientation: Int, at: Long) {
        _frames.value = RelayFrame(data, orientation, frameSeq.incrementAndGet(), at)
    }

    /** Publishes the keys' state; when they go off, the keys still waiting are dropped and their count returned. */
    internal fun keys(state: RemoteKeys): Int {
        _keys.value = state
        if (state == RemoteKeys.Ready) return 0
        epoch.incrementAndGet()
        var dropped = 0
        while (queue.tryReceive().isSuccess) dropped++
        return dropped
    }

    internal fun refused(key: FlipperKey, long: Boolean, status: Int, name: String?) {
        _refused.value = KeyRefusal(key, long, status, name, refusals.incrementAndGet())
    }

    internal companion object {
        const val QUEUE = 4
    }
}
