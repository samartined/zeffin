// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

import dev.zeffin.relay.rpc.Wire.delimited
import dev.zeffin.relay.rpc.Wire.fixed32
import dev.zeffin.relay.rpc.Wire.fixed64
import dev.zeffin.relay.rpc.Wire.len
import dev.zeffin.relay.rpc.Wire.tag
import dev.zeffin.relay.rpc.Wire.uint
import dev.zeffin.relay.rpc.Wire.varint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's bounds and skipping, on messages built by hand ([Wire]); the shared vectors are [RpcCodecTest]'s. */
class RpcReaderTest {
    private val bitmap = ByteArray(1024) { ((it * 7 + 3) % 256).toByte() }

    @Test fun readsTheFieldsItNeedsAndSkipsEveryOtherWireType() {
        val m = delimited(
            uint(1, 536870912), fixed64(90), uint(2, 17), fixed32(91), uint(3, 1), len(92, byteArrayOf(1, 2, 3)),
            uint(93, 5), len(6, len(1, byteArrayOf(9))),
        )
        val read = RpcReader().feed(m).single()
        assertEquals(536870912L, read.id)
        assertEquals(17, read.status)
        assertTrue(read.hasNext)
        assertEquals(6, read.content)  // the last length-delimited field above has_next (oneof: the last one wins)
        assertNull(read.frame)
    }

    @Test fun readsAScreenFrameAndSkipsItsUnknownFields() {
        val m = delimited(len(22, uint(7, 1) + len(1, bitmap) + fixed32(8) + uint(2, 2) + fixed64(9)))
        val read = RpcReader().feed(m).single()
        assertEquals(0L, read.id)
        assertEquals(22, read.content)
        assertArrayEquals(bitmap, read.frame!!.data)
        assertEquals(2, read.frame!!.orientation)
    }

    @Test fun readsAnInputEventsKeyAndType() {
        val read = RpcReader().feed(delimited(uint(1, 9), len(23, uint(1, 4) + fixed32(7) + uint(2, 3)))).single()
        assertEquals(4 to 3, read.input)
        assertEquals(0 to 0, RpcReader().feed(delimited(uint(1, 9), len(23, ByteArray(0)))).single().input)  // UP, PRESS: zero
        assertNull(RpcReader().feed(delimited(uint(1, 9), len(20, ByteArray(0)))).single().input)
        assertThrows(RpcError::class.java) { RpcReader().feed(delimited(uint(1, 9), len(23, tag(1, 7)))) }
    }

    @Test fun anIdKeepsItsLow32Bits() {
        val read = RpcReader().feed(delimited(uint(1, 0xFFFF_FFFFL))).single()
        assertEquals(0xFFFF_FFFFL, read.id)
    }

    @Test fun messagesComeWholeWhateverTheChunks() {
        val stream = Wire.reply(536870913) + Wire.frame(bitmap, 1) + Wire.reply(536870914, status = 15) + Wire.reply(7, hasNext = true)
        val whole = RpcReader().feed(stream).map { Triple(it.id, it.status, it.hasNext) }
        assertEquals(4, whole.size)
        for (cut in 1 until stream.size) {
            val r = RpcReader()
            val parts = r.feed(stream.copyOfRange(0, cut)) + r.feed(stream.copyOfRange(cut, stream.size))
            assertEquals("cut at $cut", whole, parts.map { Triple(it.id, it.status, it.hasNext) })
            assertEquals(0, r.pending)
        }
        val r = RpcReader()
        val bytes = stream.flatMap { b -> r.feed(byteArrayOf(b)) }
        assertEquals(whole, bytes.map { Triple(it.id, it.status, it.hasNext) })
    }

    @Test fun boundsOnTheLengthPrefix() {
        assertThrows(RpcError::class.java) { RpcReader().feed(byteArrayOf(0)) }  // an empty message is never sent
        assertThrows(RpcError::class.java) { RpcReader().feed(varint(65537)) }
        assertThrows(RpcError::class.java) { RpcReader().feed(byteArrayOf(-1, -1, -1, -1, -1)) }
        // the largest message is read; until it is whole, the reader holds only what came
        val biggest = delimited(uint(1, 1), len(92, ByteArray(65529)))
        assertEquals(65536 + 3, biggest.size)
        val r = RpcReader()
        assertTrue(r.feed(biggest.copyOfRange(0, 100)).isEmpty())
        assertEquals(100, r.pending)
        assertEquals(1L, r.feed(biggest.copyOfRange(100, biggest.size)).single().id)
        assertEquals(0, r.pending)
    }

    @Test fun refusesWhatProtobufCannotBe() {
        fun bad(vararg fields: ByteArray) = assertThrows(RpcError::class.java) { RpcReader().feed(delimited(*fields)) }
        bad(tag(5, 3))                                    // start group
        bad(tag(5, 4))                                    // end group
        bad(tag(5, 6))
        bad(tag(5, 7))
        bad(tag(0, 0), varint(1))                         // field 0
        bad(tag(1, 0), ByteArray(11) { -1 })              // a varint over ten bytes
        bad(tag(4, 2), varint(10), byteArrayOf(1, 2))     // a length past the message's end
        bad(tag(9, 1), byteArrayOf(1, 2, 3))              // a fixed64 past the end
        bad(tag(9, 5), byteArrayOf(1))                    // a fixed32 past the end
        bad(len(22, tag(1, 2) + varint(50)))              // inside a screen frame too
    }

    @Test fun aFrameOnlyCountsInsideGuiScreenFrame() {
        val read = RpcReader().feed(delimited(len(22, len(1, bitmap)), len(4, ByteArray(0)))).single()
        assertEquals(4, read.content)  // oneof: the last one wins
        assertNull(read.frame)
        assertFalse(read.hasNext)
    }
}
