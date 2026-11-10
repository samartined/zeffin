// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The relay finds the settle stop's reply as server/bridge/phone.py's `_after_settle_reply` does. */
class SettleMatchTest {
    private val id = RpcIds.SETTLE_MIN + 5
    private val reply = Wire.reply(id)
    private val after = Wire.frame(ByteArray(1024) { 1 })

    @Test fun findsTheReplyAfterAPartialFrameWhateverTheChunks() {
        // the link started inside a screen frame: a tail of its bitmap, then the reply, then a whole frame
        val stream = ByteArray(300) { 0x55 } + reply + after
        for (cut in 1 until stream.size) {
            val m = SettleMatch(id)
            val first = m.feed(stream.copyOfRange(0, cut))
            val rest = first ?: m.feed(stream.copyOfRange(cut, stream.size))
            val expected = stream.copyOfRange(300 + reply.size, stream.size)
            if (first != null) {
                assertArrayEquals("cut at $cut", stream.copyOfRange(300 + reply.size, cut), first)
            } else {
                assertArrayEquals("cut at $cut", expected, rest)
            }
        }
    }

    @Test fun theMarkerInsideABitmapIsNotTheReply() {
        // a frame whose data holds the marker (0x08 and the id's varint) after a plausible length byte: what follows does
        // not parse as a whole PB.Main, so it is not the reply (docs/protocol/rpc/vectors.json has the same case)
        val marker = byteArrayOf(0x08) + Wire.varint(id)
        val decoy = Wire.frame(ByteArray(40) { 0 } + byteArrayOf(16) + marker + ByteArray(10) { 7 } + ByteArray(40) { 0 })
        val m = SettleMatch(id)
        assertNull(m.feed(decoy))
        assertArrayEquals(after, m.feed(reply + after))
    }

    @Test fun aReplyWithAnotherIdOrHasNextIsNotIt() {
        val m = SettleMatch(id)
        assertNull(m.feed(Wire.reply(id + 1)))
        assertNull(m.feed(Wire.reply(id, hasNext = true)))
        assertArrayEquals(ByteArray(0), m.feed(Wire.reply(id, status = 1)))  // an error reply still ends the stop
    }

    /** The server's settle stop, or the relay's own stop without a server session (A2); never an ordinary server id. */
    @Test fun onlyForTheSettleRangeOrTheRelaysOwnStop() {
        assertThrows(IllegalArgumentException::class.java) { SettleMatch(RpcIds.SERVER_MAX) }
        assertThrows(IllegalArgumentException::class.java) { SettleMatch(RpcIds.RELAY_MAX + 1) }
        assertThrows(IllegalArgumentException::class.java) { SettleMatch(0) }
        SettleMatch(RpcIds.RELAY_MIN)
        SettleMatch(RpcIds.SETTLE_MIN)
    }
}
