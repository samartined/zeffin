// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Flipper's flow control on the serial service (characteristic …63fe0000).
 *
 * The firmware grants a window of 1024 bytes and refills it only when the window is used up
 * and its RPC buffer has drained, by notifying the new value (a big-endian uint32, 1024).
 * So the announced value replaces the counter. The counter is reduced when a write is
 * issued, not when it is acknowledged: a refill can arrive before the acknowledgement of the
 * last write, and subtracting afterwards would leave the relay short of a window the Flipper
 * thinks it granted, and it would never refill again.
 *
 * A notification of 0 means the Flipper closed its RPC session (it locked); that is reported
 * through [rpcOpen], not handled here.
 */
class CreditWriter(private val waitMs: Long = 20_000) {
    private val credit = MutableStateFlow(0)
    val rpcOpen = MutableStateFlow(false)
    var generation = 0
        private set

    val current: Int get() = credit.value

    /** A value read from or notified by the Flipper. */
    fun announce(value: Int) {
        val v = value.coerceIn(0, 65536)
        if (v > 0 && !rpcOpen.value) generation++
        rpcOpen.value = v > 0
        credit.value = v
    }

    /**
     * Writes [data] in chunks of at most [chunkSize] bytes and never more than the credit.
     * [writeChunk] performs one acknowledged write.
     */
    suspend fun write(data: ByteArray, chunkSize: Int, writeChunk: suspend (ByteArray) -> Unit) {
        require(chunkSize > 0)
        var offset = 0
        while (offset < data.size) {
            val available = credit.value
            if (available <= 0) {
                withTimeoutOrNull(waitMs) { credit.first { it > 0 } }
                    ?: throw FlipperException(
                        if (rpcOpen.value) FlipperProblem.STALLED else FlipperProblem.CLOSED,
                        if (rpcOpen.value) FlipperFailure.Stalled else FlipperFailure.RpcClosed)
                continue
            }
            val count = minOf(chunkSize, available, data.size - offset)
            // Notifications arrive on another thread: only take the credit if it did not change meanwhile.
            if (!credit.compareAndSet(available, available - count)) continue
            writeChunk(data.copyOfRange(offset, offset + count))
            offset += count
        }
    }
}
