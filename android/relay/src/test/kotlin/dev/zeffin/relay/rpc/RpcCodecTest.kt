// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

import dev.zeffin.relay.rpc.Wire.hex
import dev.zeffin.relay.rpc.Wire.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The relay's codec on the contract both sides test: docs/protocol/rpc/vectors.json, whose bytes the server's
 * test_rpc_vectors.py builds with protobuf. A change on either side fails the other. Unit tests run in the module's folder.
 */
class RpcCodecTest {
    private val vectors: JsonObject = Json.parseToJsonElement(File("../../docs/protocol/rpc/vectors.json").readText()).jsonObject

    private fun list(name: String) = vectors[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.l(key: String) = this[key]!!.jsonPrimitive.long

    @Test fun theRangesAreTheRelays() {
        val ranges = vectors["ranges"]!!.jsonObject
        fun range(name: String) = ranges[name]!!.jsonArray.map { it.jsonPrimitive.long }
        assertEquals(listOf(1L, RpcIds.SERVER_MAX), range("server"))
        assertEquals(listOf(RpcIds.SETTLE_MIN, RpcIds.SETTLE_MAX), range("settle"))
        assertEquals(listOf(RpcIds.RELAY_MIN, RpcIds.RELAY_MAX), range("relay"))
        assertEquals(listOf(0L, 0L), range("unsolicited"))
    }

    @Test fun everyRequestEncodesToItsBytes() {
        val requests = list("requests")
        assertTrue(requests.isNotEmpty())
        for (r in requests) {
            val id = r.l("command_id")
            val bytes = when (r.s("message")) {
                "gui_send_input_event_request" -> {
                    val f = r["fields"]!!.jsonObject
                    RpcEncoder.inputEvent(id, f["key_value"]!!.jsonPrimitive.int, f["type_value"]!!.jsonPrimitive.int)
                }
                "gui_start_screen_stream_request" -> RpcEncoder.startScreenStream(id)
                "gui_stop_screen_stream_request" -> RpcEncoder.stopScreenStream(id)
                else -> error("a request the relay does not send: ${r.s("message")}")
            }
            assertEquals(r.s("name"), r.s("hex"), hex(bytes))
        }
    }

    @Test fun everyKeyOfTheVectorsIsATripleTheRelayBuilds() {
        val input = list("requests").filter { it.s("message") == "gui_send_input_event_request" }
        val keys = input.map { it["fields"]!!.jsonObject["key_value"]!!.jsonPrimitive.int }.toSet()
        assertEquals((0..5).toSet(), keys)
        for (key in keys) {
            fun vector(type: Int) = input.first {
                val f = it["fields"]!!.jsonObject
                f["key_value"]!!.jsonPrimitive.int == key && f["type_value"]!!.jsonPrimitive.int == type
            }
            for (long in listOf(false, true)) {
                val parts = listOf(vector(Pb.PRESS), vector(if (long) Pb.LONG else Pb.SHORT), vector(Pb.RELEASE))
                val ids = LongArray(3) { parts[it].l("command_id") }
                assertEquals(parts.joinToString("") { it.s("hex") }, hex(RpcEncoder.keyFrame(ids, key, long)))
            }
        }
    }

    @Test fun everyRequestAndReplyReadsBack() {
        for (r in list("requests")) {
            val m = RpcReader().feed(unhex(r.s("hex"))).single()
            assertEquals(r.s("name"), r.l("command_id"), m.id)
            when (r.s("sender")) {
                "relay" -> assertTrue(r.s("name"), RpcIds.isRelay(m.id))
                "server" -> assertTrue(r.s("name"), RpcIds.isSettle(m.id))  // the settle stop the relay syncs on
                else -> error("unknown sender in ${r.s("name")}")
            }
            assertEquals(false, m.hasNext)
        }
        for (r in list("replies")) {
            val m = RpcReader().feed(unhex(r.s("hex"))).single()
            assertEquals(r.s("name"), r.l("command_id"), m.id)
            assertEquals(r.s("name"), r["command_status_value"]!!.jsonPrimitive.int, m.status)
            assertEquals(r.s("name"), r["has_next"]!!.jsonPrimitive.boolean, m.hasNext)
            assertEquals(r.s("name"), mapOf("empty" to 4, "gui_screen_frame" to Pb.GUI_SCREEN_FRAME)[r.s("content")], m.content)
            if (r.s("content") == "gui_screen_frame") {
                val f = r["fields"]!!.jsonObject
                assertArrayEquals(unhex(f["data_hex"]!!.jsonPrimitive.content), m.frame!!.data)
                assertEquals(f["orientation_value"]!!.jsonPrimitive.int, m.frame!!.orientation)
            }
        }
    }

    @Test fun theSettleReplyIsFoundAndItsLookalikeIsNot() {
        val stop = list("requests").single { it.s("name") == "settle.stop" }
        val replies = list("replies").associateBy { it.s("name") }
        val lookalike = unhex(replies.getValue("screen_frame.settle_lookalike").s("hex"))
        val reply = unhex(replies.getValue("settle.stop.ok").s("hex"))
        val frame = unhex(replies.getValue("screen_frame").s("hex"))
        val m = SettleMatch(stop.l("command_id"))
        assertEquals(null, m.feed(lookalike))
        assertArrayEquals(frame, m.feed(reply + frame))
        // and at every cut of the whole stream
        val stream = lookalike + reply + frame
        for (cut in 1 until stream.size step 3) {
            val c = SettleMatch(stop.l("command_id"))
            val first = c.feed(stream.copyOfRange(0, cut))
            val rest = first?.plus(stream.copyOfRange(cut, stream.size)) ?: c.feed(stream.copyOfRange(cut, stream.size))
            assertArrayEquals("cut at $cut", frame, rest)
        }
    }

    @Test fun theWholeSetReadsTheSameAtEveryCut() {
        val all = (list("requests") + list("replies")).map { unhex(it.s("hex")) }
        val stream = all.fold(ByteArray(0)) { a, b -> a + b }
        val whole = RpcReader().feed(stream).map { it.id to it.status }
        assertEquals(all.size, whole.size)
        for (cut in 1 until stream.size step 7) {
            val r = RpcReader()
            val got = r.feed(stream.copyOfRange(0, cut)) + r.feed(stream.copyOfRange(cut, stream.size))
            assertEquals("cut at $cut", whole, got.map { it.id to it.status })
        }
    }
}
