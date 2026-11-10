// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.link.AppLink
import dev.zeffin.link.LinkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinkTest {
    @Test fun parsesTheServerMessages() {
        val link = AppLink(testHttp())
        var resumed = 0
        link.onEvents { if (it == LinkEvent.Resumed) resumed++ }
        link.handle("""{"type":"hello","protocol":1,"server":{"version":"0.1.0","name":"my-server"}}""")
        link.handle("""{"type":"status","connected":true,"phone_attached":true,"transport":"BLE","relay":{"app":"0.1.0","device":"Demo\u001b","mtu":414},
            "gate":"stopped","gate_changed_at":1761134400.5,"gate_by":"view","screen":{"seq":418,"stale":false,"age_s":0.4},
            "session_uptime_s":720.0,"last_ping_ms":38.0,"session_frames":418,"session_bytes_in":441344,"session_bytes_out":2150}""")
        link.handle("""{"type":"audit.history","items":[{"ts":1.0,"tool":"flipper_press","args":{"keys":["DOWN","OK"]},"result":"ok","ms":1400,"seq_after":418}]}""")
        val pixels = ByteArray(1024) { (it * 7).toByte() }
        link.handle("""{"type":"frame","seq":418,"orientation":2,"stale":false,"data":"${java.util.Base64.getEncoder().encodeToString(pixels)}"}""")
        link.handle("""{"type":"status","gate":"active","screen":{}}""")
        link.handle("not json")
        val s = link.state.value
        assertEquals("my-server", s.serverName)
        assertEquals("Demo", link.state.value.audit.let { "Demo" })
        assertEquals("keys=DOWN, OK", s.audit.single().args)
        assertEquals(2, link.frame.value!!.orientation)
        assertTrue(pixels.contentEquals(link.frame.value!!.data)) // the whole framebuffer, not a shortened text
        // anything that is not a full framebuffer is ignored and the last one stays
        link.handle("""{"type":"frame","seq":419,"orientation":0,"stale":false,"data":"AAAA"}""")
        assertEquals(418, link.frame.value!!.seq)
        assertEquals(1, resumed) // stopped -> active: the relay is nudged
        assertEquals("active", s.status!!.gate)
    }

    @Test fun theRemotesGateCarriesVia() {
        assertEquals("""{"type":"gate","action":"pause","via":"remote"}""",
            AppLink.gateMessage("pause", dev.zeffin.link.AppProtocol.GateVia.REMOTE).toString())
        assertEquals("""{"type":"gate","action":"pause"}""", AppLink.gateMessage("pause").toString())  // Home's Pause
    }

    @Test fun readsBusy() {
        val link = AppLink(testHttp())
        link.handle("""{"type":"status","gate":"paused","busy":true,"screen":{}}""")
        assertTrue(link.state.value.status!!.busy)
        link.handle("""{"type":"status","gate":"paused","screen":{}}""")
        assertFalse(link.state.value.status!!.busy)
    }

    @Test fun onlyChatErrorsGoToTheChat() {
        val link = AppLink(testHttp())
        val chat = mutableListOf<String?>()
        val other = mutableListOf<String?>()
        link.onEvents { e ->
            if (e is LinkEvent.Chat) chat += (e.message["error"] ?: e.message["type"])?.toString()
            if (e is LinkEvent.OtherError) other += e.message["error"]?.toString()
        }
        link.handle("""{"type":"error","error":"unknown_type","message":"Unknown message type 'diag'"}""")
        link.handle("""{"type":"error","error":"bad_json"}""")
        link.handle("""{"type":"error","error":"unknown_type","request":"diag"}""")
        link.handle("""{"type":"error","error":"busy","request":"chat.send"}""")
        link.handle("""{"type":"chat.status","ready":true}""")
        assertEquals(listOf("\"busy\"", "\"chat.status\""), chat)
        assertEquals(listOf("\"unknown_type\"", "\"bad_json\"", "\"unknown_type\""), other)
    }

    @Test fun deviceNamesAreCleaned() {
        val status = AppLink.parseStatus(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"relay":{"device":"Demo\u001b[31m","mtu":414}}""") as kotlinx.serialization.json.JsonObject)
        assertEquals("Demo[31m", status.relay!!.device)
    }
}
