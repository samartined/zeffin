// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

import java.io.ByteArrayOutputStream

/** A protobuf writer for tests, independent of [RpcEncoder]: it builds the messages the reader must read or refuse. */
object Wire {
    fun varint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var v = value
        while (true) {
            val low = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { write(low); break }
            write(low or 0x80)
        }
    }.toByteArray()

    fun tag(field: Int, wire: Int) = varint((field.toLong() shl 3) or wire.toLong())
    fun uint(field: Int, value: Long) = tag(field, 0) + varint(value)
    fun len(field: Int, bytes: ByteArray) = tag(field, 2) + varint(bytes.size.toLong()) + bytes
    fun fixed64(field: Int) = tag(field, 1) + ByteArray(8) { it.toByte() }
    fun fixed32(field: Int) = tag(field, 5) + ByteArray(4) { (it + 1).toByte() }

    fun delimited(vararg fields: ByteArray): ByteArray {
        val main = fields.fold(ByteArray(0)) { acc, f -> acc + f }
        return varint(main.size.toLong()) + main
    }

    /** An empty reply: `command_id`, and `command_status` and `has_next` when not zero. */
    fun reply(id: Long, status: Int = 0, hasNext: Boolean = false): ByteArray = delimited(
        uint(1, id),
        if (status != 0) uint(2, status.toLong()) else ByteArray(0),
        if (hasNext) uint(3, 1) else ByteArray(0),
        len(4, ByteArray(0)),
    )

    /** An unsolicited `gui_screen_frame` (id 0). */
    fun frame(data: ByteArray, orientation: Int = 0): ByteArray = delimited(
        len(22, len(1, data) + (if (orientation != 0) uint(2, orientation.toLong()) else ByteArray(0))),
    )

    /** A request from the server: `gui_stop_screen_stream_request` (21), or another content field. */
    fun request(id: Long, content: Int = 21, hasNext: Boolean = false, body: ByteArray = ByteArray(0)): ByteArray = delimited(
        uint(1, id),
        if (hasNext) uint(3, 1) else ByteArray(0),
        len(content, body),
    )

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
