// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

/**
 * What the relay reads of one `PB.Main`; [content] is the field number of its `content`, 0 when it has none. [input] is a
 * `gui_send_input_event_request`'s key and type, read so the relay can check its own keys before writing them.
 */
internal class RpcMessage(
    val id: Long,
    val status: Int,
    val hasNext: Boolean,
    val content: Int,
    val frame: ScreenData?,
    val input: Pair<Int, Int>? = null,
)

/** A `gui_screen_frame`'s bytes and orientation, as they came: untrusted data. */
internal class ScreenData(val data: ByteArray, val orientation: Int)

/**
 * Splits the link's bytes into `PB.Main` messages and reads each one, in chunks of any size as the link delivers them.
 *
 * Bounds: a length prefix of at most [Pb.MAX_PREFIX] bytes with a value in `1 … 65536` (server/bridge/framing.py), so at
 * most one message plus one chunk is ever buffered. Fields other than the ones [RpcMessage] holds are skipped by their wire
 * type; wire types 3, 4, 6 and 7, a varint over ten bytes, or a length past the message's end is an [RpcError].
 */
internal class RpcReader {
    private var buf = ByteArray(INITIAL)
    private var start = 0
    private var end = 0

    /** The messages this chunk completes, in order. Throws [RpcError]; the reader is then of no further use. */
    fun feed(chunk: ByteArray): List<RpcMessage> {
        append(chunk)
        val out = mutableListOf<RpcMessage>()
        var next = header()
        while (next != null && end - start - next.first >= next.second) {
            val (n, size) = next
            out += parse(buf, start + n, start + n + size)
            start += n + size
            next = header()
        }
        if (start == end) {
            start = 0
            end = 0
        }
        return out
    }

    /** The next message's (prefix length, message length), or null while its prefix is not complete. */
    private fun header(): Pair<Int, Int>? {
        var value = 0L
        var n = 0
        var done = false
        while (!done && start + n < end) {
            val b = buf[start + n].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl (7 * n))
            n++
            done = b and 0x80 == 0
            if (!done && n == Pb.MAX_PREFIX) throw RpcError("invalid varint length")
        }
        if (done && (value <= 0 || value > Pb.MAX_MESSAGE)) throw RpcError("invalid message length")
        return if (done) n to value.toInt() else null
    }

    /** Bytes waiting for the rest of their message. */
    val pending: Int get() = end - start

    private fun append(chunk: ByteArray) {
        if (end + chunk.size > buf.size) {
            val live = end - start
            val next = if (live + chunk.size > buf.size) ByteArray(maxOf(buf.size * 2, live + chunk.size)) else buf
            System.arraycopy(buf, start, next, 0, live)
            buf = next
            start = 0
            end = live
        }
        System.arraycopy(chunk, 0, buf, end, chunk.size)
        end += chunk.size
    }

    companion object {
        private const val INITIAL = 2048

        /** One `PB.Main` from [from] to [to]. Throws [RpcError]. */
        fun parse(bytes: ByteArray, from: Int, to: Int): RpcMessage {
            val r = Cursor(bytes, from, to)
            var id = 0L
            var status = 0
            var hasNext = false
            var content = 0
            var frame: ScreenData? = null
            var input: Pair<Int, Int>? = null
            while (r.pos < to) {
                val (field, wire) = r.tag()
                when (wire) {
                    VARINT -> {
                        val v = r.varint()
                        when (field) {
                            Pb.COMMAND_ID -> id = v and 0xFFFF_FFFFL
                            Pb.COMMAND_STATUS -> status = v.toInt()
                            Pb.HAS_NEXT -> hasNext = v != 0L
                        }
                    }
                    LEN -> {
                        val (at, len) = r.bytes()
                        if (field > Pb.HAS_NEXT && field <= Pb.MAX_CONTENT) content = field
                        if (field == Pb.GUI_SCREEN_FRAME) frame = screen(bytes, at, at + len)
                        if (field == Pb.GUI_SEND_INPUT_EVENT_REQUEST) input = inputEvent(bytes, at, at + len)
                    }
                    else -> r.skip(wire)
                }
            }
            if (content != Pb.GUI_SCREEN_FRAME) frame = null
            if (content != Pb.GUI_SEND_INPUT_EVENT_REQUEST) input = null
            return RpcMessage(id, status, hasNext, content, frame, input)
        }

        private fun inputEvent(bytes: ByteArray, from: Int, to: Int): Pair<Int, Int> {
            val r = Cursor(bytes, from, to)
            var key = 0
            var type = 0
            while (r.pos < to) {
                val (field, wire) = r.tag()
                when {
                    wire == VARINT && field == Pb.INPUT_KEY -> key = r.varint().toInt()
                    wire == VARINT && field == Pb.INPUT_TYPE -> type = r.varint().toInt()
                    wire == VARINT -> r.varint()
                    wire == LEN -> r.bytes()
                    else -> r.skip(wire)
                }
            }
            return key to type
        }

        private fun screen(bytes: ByteArray, from: Int, to: Int): ScreenData {
            val r = Cursor(bytes, from, to)
            var data = ByteArray(0)
            var orientation = 0
            while (r.pos < to) {
                val (field, wire) = r.tag()
                when {
                    wire == LEN && field == Pb.FRAME_DATA -> {
                        val (at, len) = r.bytes()
                        data = bytes.copyOfRange(at, at + len)
                    }
                    wire == VARINT && field == Pb.FRAME_ORIENTATION -> orientation = r.varint().toInt()
                    wire == VARINT -> r.varint()
                    wire == LEN -> r.bytes()
                    else -> r.skip(wire)
                }
            }
            return ScreenData(data, orientation)
        }

        private const val VARINT = 0
        private const val I64 = 1
        private const val LEN = 2
        private const val I32 = 5
    }

    private class Cursor(val bytes: ByteArray, var pos: Int, val to: Int) {
        fun tag(): Pair<Int, Int> {
            val t = varint()
            val field = (t ushr 3)
            if (field <= 0 || field > MAX_FIELD) throw RpcError("invalid field number")
            return field.toInt() to (t and 7).toInt()
        }

        fun varint(): Long {
            var value = 0L
            for (i in 0 until MAX_VARINT) {
                if (pos >= to) throw RpcError("truncated varint")
                val b = bytes[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl (7 * i))
                if (b and 0x80 == 0) return value
            }
            throw RpcError("varint over ten bytes")
        }

        /** A length-delimited field: where its bytes start, and how many. */
        fun bytes(): Pair<Int, Int> {
            val len = varint()
            if (len < 0 || len > to - pos) throw RpcError("length past the message's end")
            val at = pos
            pos += len.toInt()
            return at to len.toInt()
        }

        fun skip(wire: Int) {
            val n = when (wire) {
                I64 -> 8
                I32 -> 4
                else -> throw RpcError("unsupported wire type $wire")
            }
            if (n > to - pos) throw RpcError("field past the message's end")
            pos += n
        }

        private companion object {
            const val MAX_VARINT = 10
            const val MAX_FIELD = (1L shl 29) - 1
        }
    }
}
