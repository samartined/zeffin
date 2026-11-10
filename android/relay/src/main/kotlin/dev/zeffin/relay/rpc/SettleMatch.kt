// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

/**
 * Finds the reply to the server's settle stop in the Flipper's bytes, as server/bridge/phone.py's `_after_settle_reply`
 * does, so the relay starts reading at the same message boundary as the server. Without a server session the relay syncs
 * the same way on its own stop, whose id is from the relay's range.
 *
 * The reply is a `PB.Main` whose first field is `command_id` (the firmware writes fields in order), so it starts with its
 * one-byte length, `0x08` and the id's varint. A candidate must also parse as a whole `PB.Main` with that id and no
 * `has_next`, so the same bytes inside a screen frame's bitmap are not taken for it.
 */
internal class SettleMatch(private val id: Long) {
    init {
        require(RpcIds.isSettle(id) || RpcIds.isRelay(id))
    }

    private val marker = byteArrayOf(0x08) + RpcEncoder.varint(id)
    private var buf = ByteArray(0)

    /** The bytes after the stop's reply when this chunk completes it, else null. */
    fun feed(data: ByteArray): ByteArray? {
        buf += data
        var keep = -1  // where an incomplete candidate starts: kept whole for the next chunk
        var after: ByteArray? = null
        var at = indexOf(1)
        while (at != -1 && keep == -1 && after == null) {
            val size = buf[at - 1].toInt() and 0xFF
            val end = at + size
            when {
                size < marker.size || size >= 0x80 -> Unit  // not the reply's one-byte length
                buf.size < end -> keep = at - 1
                isReply(at, end) -> after = buf.copyOfRange(end, buf.size)
            }
            at = indexOf(at + 1)
        }
        // otherwise a marker may begin in the last bytes, after its length byte
        if (after == null) buf = buf.copyOfRange(if (keep >= 0) keep else maxOf(0, buf.size - marker.size), buf.size)
        return after
    }

    private fun isReply(from: Int, to: Int): Boolean {
        val reply = try {
            RpcReader.parse(buf, from, to)
        } catch (_: RpcError) {
            null
        }
        return reply != null && reply.id == id && !reply.hasNext
    }

    private fun indexOf(from: Int): Int {
        var i = from
        while (i <= buf.size - marker.size) {
            var match = true
            for (j in marker.indices) {
                if (buf[i + j] != marker[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
