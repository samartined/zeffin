// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

import java.io.ByteArrayOutputStream

/**
 * Builds the relay's three requests, each one varint-delimited `PB.Main`, byte for byte as the server's protobuf would
 * (docs/protocol/rpc/vectors.json): proto3 leaves out fields at their zero value, so `UP`, `PRESS` and `has_next` false
 * are absent from the bytes. The relay never sends id 0 and never sets `has_next`.
 */
internal object RpcEncoder {
    fun inputEvent(id: Long, key: Int, type: Int): ByteArray =
        delimited(id, Pb.GUI_SEND_INPUT_EVENT_REQUEST, ByteArrayOutputStream().apply {
            uint(Pb.INPUT_KEY, key.toLong())
            uint(Pb.INPUT_TYPE, type.toLong())
        }.toByteArray())

    fun startScreenStream(id: Long): ByteArray = delimited(id, Pb.GUI_START_SCREEN_STREAM_REQUEST, ByteArray(0))

    fun stopScreenStream(id: Long): ByteArray = delimited(id, Pb.GUI_STOP_SCREEN_STREAM_REQUEST, ByteArray(0))

    /**
     * One key: `PRESS`, then `SHORT` or `LONG`, then `RELEASE`, in one frame (the firmware drops `PRESS, RELEASE, SHORT`).
     * [ids] are the three messages' ids, in that order.
     */
    fun keyFrame(ids: LongArray, key: Int, long: Boolean): ByteArray {
        require(ids.size == 3)
        return inputEvent(ids[0], key, Pb.PRESS) +
            inputEvent(ids[1], key, if (long) Pb.LONG else Pb.SHORT) +
            inputEvent(ids[2], key, Pb.RELEASE)
    }

    private fun delimited(id: Long, field: Int, content: ByteArray): ByteArray {
        require(id in 1..0xFFFF_FFFFL) { "an id the relay sends is a uint32 above 0" }
        val main = ByteArrayOutputStream().apply {
            uint(Pb.COMMAND_ID, id)
            varint(tag(field, LEN))
            varint(content.size.toLong())
            write(content)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            varint(main.size.toLong())
            write(main)
        }.toByteArray()
    }

    private const val VARINT = 0
    private const val LEN = 2

    private fun tag(field: Int, wire: Int) = (field.toLong() shl 3) or wire.toLong()

    /** A uint32 or enum field; zero is left out, as proto3 does. */
    private fun ByteArrayOutputStream.uint(field: Int, value: Long) {
        if (value == 0L) return
        varint(tag(field, VARINT))
        varint(value)
    }

    fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (true) {
            val low = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                write(low)
                return
            }
            write(low or 0x80)
        }
    }

    fun varint(value: Long): ByteArray = ByteArrayOutputStream().apply { varint(value) }.toByteArray()
}
