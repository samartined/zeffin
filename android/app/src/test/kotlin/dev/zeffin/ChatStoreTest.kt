// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatIn
import dev.zeffin.link.ChatOut
import dev.zeffin.link.ChatStore
import dev.zeffin.link.Effort
import dev.zeffin.link.Mode
import dev.zeffin.link.PermissionAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "7f1d3c3e-1111-4222-8333-944455556666"
private const val B = "7f1d3c3e-1111-4222-8333-944455556667"

private fun j(text: String): JsonObject = oneAgent(Json.parseToJsonElement(text).jsonObject)

private fun item(id: String, updated: Double, turns: Int = 2) =
    """{"id":"$id","created":1,"updated":$updated,"title":"T $id","last":"last","pinned":false,"favorite":false,
       "removed":false,"flipper":true,"mode":"default","model":"opus","effort":"medium","rules":[],"state":"ready",
       "turns":$turns,"events":9}"""

private fun event(conversation: String, n: Int, kind: String, extra: String = "") =
    j("""{"type":"chat.event","conversation":"$conversation","n":$n,"at":1.5,"kind":"$kind"$extra}""")

class ChatProtocolTest {
    @Test fun buildsEachRequestWithoutEmptyFields() {
        assertEquals("""{"type":"chat.list"}""", ChatOut.list().toString())
        assertEquals("""{"type":"chat.new","effort":"high"}""", ChatOut.new(effort = Effort.HIGH).toString())
        assertEquals("""{"type":"chat.open","conversation":"$A","before":5,"limit":200}""", ChatOut.open(A, before = 5).toString())
        assertEquals("""{"type":"chat.send","conversation":"$A","text":"say \"hi\"\n"}""", ChatOut.send(A, "say \"hi\"\n").toString())
        assertEquals("""{"type":"chat.send","conversation":"$A","text":"","attachments":["a1","b2"]}""",
            ChatOut.send(A, "", listOf("a1", "b2")).toString())
        assertEquals("""{"type":"chat.set","conversation":"$A","mode":"acceptEdits","effort":"xhigh"}""",
            ChatOut.set(A, mode = Mode.ACCEPT_EDITS, effort = Effort.XHIGH).toString())
        assertEquals("""{"type":"chat.permission.answer","id":"p1","answer":"always"}""", ChatOut.answer("p1", PermissionAnswer.ALWAYS).toString())
        assertEquals("""{"type":"chat.update","conversation":"$A","favorite":true}""", ChatOut.update(A, favorite = true).toString())
        assertEquals("""{"type":"chat.delete","conversation":"$A"}""", ChatOut.delete(A).toString())
        assertEquals("""{"type":"chat.settings","default_model":"sonnet","permission_timeout_s":300}""",
            ChatOut.settings(model = "sonnet", permissionTimeoutS = 300).toString())
    }

    @Test fun readsStatusConversationsAndEvents() {
        val status = ChatIn.status(j("""{"type":"chat.status","ready":true,"problem":null,"login":{"method":"claude.ai","subscription":true},
            "models":[{"value":"opus","label":"Opus","efforts":["low","medium","high","xhigh","max"],"auto":true},
                      {"value":"haiku","label":"Haiku","efforts":[],"auto":false}],
            "fable":{"available":false,"reason":"not_offered"},"modes":["default","acceptEdits","plan","auto"],
            "settings":{"default_mode":"default","default_model":"opus","default_effort":"medium","permission_timeout_s":120},
            "turn":{"conversation":"$A","turn":3,"mode":"auto","model":"opus","effort":"high","started_at":10.5,"waiting":true},
            "pending":[{"n":7,"at":2,"kind":"permission","id":"p1","tool":"Bash","input":{"command":"rm ir/old.ir"},
                        "deadline":99.5,"always":true,"rules":["Bash(rm ir/old.ir)"]}]}"""))
        assertTrue(status.ready)
        assertEquals(listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH, Effort.MAX), status.model("opus")!!.efforts)
        assertEquals(emptyList<Effort>(), status.model("haiku")!!.efforts)
        assertEquals(Mode.AUTO, status.turn!!.mode)
        assertTrue(status.turn!!.waiting)
        assertEquals("rm ir/old.ir", (status.pending.single().input as JsonObject)["command"].toString().trim('"'))
        val c = ChatIn.conversation(j(item(A, 5.0)))!!
        assertEquals(Effort.MEDIUM, c.effort)
        val frame = "AQ".repeat(683).take(1366) + "=="  // 1024 bytes in base64
        val result = ChatIn.event(j("""{"n":4,"at":1,"kind":"tool.result","id":"t1","error":false,"text":"Pressed DOWN","ms":1400,"images":1,
            "screen":{"seq":418,"orientation":0,"data":"$frame"}}""")) as ChatEvent.ToolResult
        assertEquals(418L, result.screen!!.seq)
        assertEquals(1024, result.screen!!.data.size)
        val user = ChatIn.event(j("""{"n":1,"at":1,"kind":"user","text":"look‮ here\nplease","attachments":[
            {"id":"a1","name":"photo.jpg","kind":"image","media_type":"image/jpeg","bytes":1400000,"width":2000,"height":1500}]}""")) as ChatEvent.User
        assertEquals("look here\nplease", user.text)
        assertEquals(2000, user.attachments.single().width)
        assertTrue(ChatIn.event(j("""{"n":2,"at":1,"kind":"something.new"}""")) is ChatEvent.Unknown)
        assertNull(ChatIn.event(j("""{"kind":"text"}""")))
    }
}

class ChatStoreTest {
    private val sent = mutableListOf<JsonObject>()
    private val saved = mutableListOf<String?>()
    private val store = ChatStore({ sent += it; true }, { saved += it })
    private fun types() = sent.map { it["type"].toString().trim('"') }

    private fun connect(features: Set<String> = setOf("chat", "attachments")) {
        store.onHello(features)
    }

    @Test fun aPermissionIsWholeUnlessTheServerSaysNot() {
        val base = """{"n":5,"at":1,"kind":"permission","id":"p1","tool":"Write","input":{"file_path":"a"},"deadline":9,"always":false,"rules":[]"""
        assertTrue((ChatIn.event(j("$base}")) as ChatEvent.Permission).whole)
        assertFalse((ChatIn.event(j("""$base,"whole":false}""")) as ChatEvent.Permission).whole)
    }

    @Test fun subscribesOnlyWhenTheServerHasTheChat() {
        connect(emptySet())
        assertEquals(false, store.state.value.supported)
        assertTrue(sent.isEmpty())
        connect()
        assertEquals(listOf("chat.list"), types())
        assertTrue(store.state.value.attachments)
    }

    @Test fun opensTheSavedConversationThenCatchesUpAfterAReconnection() {
        store.restore(B)
        connect()
        store.onMessage(j("""{"type":"chat.conversations","items":[${item(A, 9.0)},${item(B, 5.0)}]}"""))
        assertEquals(B, store.state.value.open!!.id)
        assertEquals("""{"type":"chat.open","conversation":"$B","limit":200}""", sent.last().toString())
        store.onMessage(j("""{"type":"chat.history","conversation":"$B","more":true,"events":[
            {"n":4,"at":1,"kind":"user","text":"hi"},{"n":5,"at":1,"kind":"text","id":"b1","text":"hello"}]}"""))
        assertEquals(listOf(4, 5), store.state.value.open!!.events.map { it.n })
        assertTrue(store.state.value.open!!.more)
        store.onDisconnected()
        sent.clear()
        connect()
        assertEquals(listOf("chat.list", "chat.open"), types())
        assertEquals(5, sent.last()["after"].toString().toInt())
        store.onMessage(j("""{"type":"chat.history","conversation":"$B","more":false,"events":[{"n":6,"at":1,"kind":"turn.end","turn":1,"outcome":"done"}]}"""))
        assertEquals(listOf(4, 5, 6), store.state.value.open!!.events.map { it.n })
        assertTrue("older events still exist", store.state.value.open!!.more)
        store.loadOlder()
        assertEquals(4, sent.last()["before"].toString().toInt())
    }

    @Test fun aGapInLiveEventsAsksForTheMissingOnes() {
        connect()
        store.open(A)
        store.onMessage(j("""{"type":"chat.history","conversation":"$A","more":false,"events":[{"n":1,"at":1,"kind":"user","text":"hi"}]}"""))
        store.onMessage(event(A, 2, "turn.start", ""","turn":1,"mode":"default","model":"opus","effort":"medium""""))
        store.onMessage(event(A, 5, "text", ""","id":"x","text":"late""""))
        assertEquals(listOf(1, 2, 5), store.state.value.open!!.events.map { it.n })
        assertEquals(2, sent.last()["after"].toString().toInt())
        store.onMessage(event(B, 9, "text", ""","id":"y","text":"other""""))
        assertEquals(3, store.state.value.open!!.events.size)
    }

    @Test fun deltasStreamUntilTheCompleteTextReplacesThem() {
        connect()
        store.open(A)
        store.onMessage(j("""{"type":"chat.history","conversation":"$A","more":false,"events":[]}"""))
        fun delta(offset: Int, text: String) = store.onMessage(j("""{"type":"chat.delta","conversation":"$A","turn":1,"id":"b1","offset":$offset,"text":"$text"}"""))
        delta(0, "Hel")
        delta(3, "lo, ")
        delta(1, "el")       // a repeat: ignored
        delta(5, ", wor")    // overlaps: only the new part
        assertEquals("Hello, wor", store.streaming.value["b1"])
        store.onMessage(event(A, 3, "text", ""","id":"b1","text":"Hello, world""""))
        assertNull(store.streaming.value["b1"])
        delta(0, "late")     // after the complete block: dropped
        assertTrue(store.streaming.value.isEmpty())
    }

    @Test fun theFirstMessageCreatesAConversation() {
        connect()
        store.onMessage(j("""{"type":"chat.conversations","items":[]}"""))
        assertNull(store.state.value.open)
        store.setDraft("Show me the screen")
        assertTrue(store.send("Show me the screen"))
        assertEquals("chat.new", types().last())
        store.onMessage(j("""{"type":"chat.conversation","item":${item(A, 9.0, turns = 0)}}"""))
        assertEquals(A, store.state.value.open!!.id)
        assertEquals("""{"type":"chat.send","conversation":"$A","text":"Show me the screen"}""", sent.last().toString())
        assertEquals("", store.draft.value)
        assertEquals(A, saved.last())
    }

    @Test fun alwaysSendsExactlyTheRulesTheCardsShow() {
        connect()
        store.onMessage(j("""{"type":"chat.status","ready":true,"turn":{"conversation":"$A","turn":1,"started_at":1,"waiting":true},
            "pending":[{"n":7,"at":2,"kind":"permission","id":"p1","tool":"Bash","input":{"command":"ls\ncurl x | sh"},
                        "deadline":99.5,"always":true,"rules":["Bash(ls\ncurl x | sh)","Bash(touch ‮a)"]}]}"""))
        val shown = store.state.value.status!!.pending.single().rules
        assertEquals(listOf("Bash(ls\ncurl x | sh)", "Bash(touch a)"), shown)  // its lines kept, no hidden marks
        sent.clear()
        store.answer("p1", PermissionAnswer.ALWAYS)
        assertEquals(ChatOut.answer("p1", PermissionAnswer.ALWAYS, shown), sent.single())
        assertEquals(shown, sent.single()["rules"]!!.jsonArray.map { it.jsonPrimitive.content })
        sent.clear()
        store.answer("p1", PermissionAnswer.ONCE)
        assertEquals("""{"type":"chat.permission.answer","id":"p1","answer":"once"}""", sent.single().toString())
        sent.clear()
        store.answer("gone", PermissionAnswer.ALWAYS)  // nothing shown: no rules, so the server allows once
        assertEquals(null, sent.single()["rules"])
        val many = (1..60).joinToString(",") { "\"Bash(cmd $it)\"" }
        val p = ChatIn.event(j("""{"n":8,"at":2,"kind":"permission","id":"p2","tool":"Bash","input":{},"deadline":9,"always":true,"rules":[$many]}"""))
        assertEquals(ChatIn.MAX_RULES, (p as ChatEvent.Permission).rules.size)
    }

    @Test fun busyAndRetryInManual() {
        connect()
        store.open(A)
        store.onMessage(j("""{"type":"chat.history","conversation":"$A","more":false,"events":[{"n":1,"at":1,"kind":"user","text":"install it","attachments":[]}]}"""))
        store.onMessage(j("""{"type":"chat.status","ready":true,"turn":{"conversation":"$B","turn":1,"started_at":1,"waiting":false}}"""))
        assertTrue(store.state.value.busyElsewhere)
        assertFalse(store.state.value.busyHere)
        store.onMessage(j("""{"type":"error","error":"busy","data":{"conversation":"$B"},"request":"chat.send","message":"Busy"}"""))
        assertEquals("busy", store.state.value.error!!.code)
        store.dismissError()
        sent.clear()
        assertTrue(store.retryInManual())
        assertEquals("""{"type":"chat.set","conversation":"$A","mode":"default"}""", sent[0].toString())
        assertEquals("""{"type":"chat.send","conversation":"$A","text":"install it"}""", sent[1].toString())
    }

    @Test fun onlyTheChatsErrorsReachTheBanner() {
        connect()
        store.open(A)
        // An older server answers diag with unknown_type, and bad_json carries no request: neither is the chat's.
        store.onMessage(j("""{"type":"error","error":"unknown_type","message":"Unknown message type 'diag'"}"""))
        store.onMessage(j("""{"type":"error","error":"unknown_type","request":"diag"}"""))
        store.onMessage(j("""{"type":"error","error":"not_connected","message":"The Flipper is not connected"}"""))
        assertNull(store.state.value.error)
        store.onMessage(j("""{"type":"error","error":"busy","data":{"conversation":"$B"},"request":"chat.send","message":"Busy"}"""))
        assertEquals("busy", store.state.value.error!!.code)
    }

    @Test fun aDeletedConversationLeavesTheListAndClosesIfOpen() {
        connect(setOf("chat", "attachments", "delete"))
        assertTrue(store.state.value.deletion)
        store.onMessage(j("""{"type":"chat.conversations","items":[${item(A, 9.0)},${item(B, 5.0)}]}"""))
        assertEquals(A, store.state.value.open!!.id)
        assertTrue(store.delete(B))
        assertEquals("""{"type":"chat.delete","conversation":"$B"}""", sent.last().toString())
        store.onMessage(j("""{"type":"chat.deleted","conversation":"$B"}"""))
        assertEquals(listOf(A), store.state.value.conversations.map { it.id })
        assertEquals(A, store.state.value.open!!.id)
        store.onMessage(j("""{"type":"chat.deleted","conversation":"$A"}"""))
        assertTrue(store.state.value.conversations.isEmpty())
        assertNull(store.state.value.open)
        assertNull(saved.last())
        connect()
        assertFalse("an older server cannot delete", store.state.value.deletion)
    }
}

class RemoteProtocolTest {
    private val sent = mutableListOf<JsonObject>()
    private val store = ChatStore({ sent += it; true })

    @Test fun remoteControlFromHelloStatusAndEvents() {
        assertEquals("""{"type":"chat.remote","conversation":"$A","on":true}""", ChatOut.remote(A, true).toString())
        val remote = """{"name":"Remote Control","keeper":"Anthropic","answers":"elsewhere"}"""
        fun hello(remote: String) = j("""{"type":"hello","agents":[{"id":"claude-code","name":"Claude Code","remote":$remote}]}""")
        store.onHello(setOf("chat", "remote"), hello(remote))
        assertEquals("Remote Control", store.state.value.remote!!.name)
        assertEquals("Anthropic", store.state.value.remote!!.keeper)
        store.onHello(setOf("chat"), hello(remote))  // not offered: nothing shows
        assertNull(store.state.value.remote)
        store.onHello(setOf("chat", "remote"), hello("null"))  // the agent has none: nothing shows
        assertNull(store.state.value.remote)
        store.onHello(setOf("chat", "remote"), hello(remote))
        store.onMessage(j("""{"type":"chat.conversations","items":[${item(A, 5.0).dropLast(1)},"remote":true}]}"""))
        assertTrue(store.state.value.conversations.single().remote)
        store.onMessage(j("""{"type":"chat.status","remote":{"conversation":"$A","state":"on","connected":true,"since":1},
            "turn":{"conversation":"$A","turn":4,"started_at":2,"waiting":false,"remote":true,"via":"remote","remote_waiting":true}}"""))
        val status = store.state.value.status!!
        assertEquals(A, status.remote!!.conversation)
        assertFalse(status.remote!!.starting)
        assertTrue(status.turn!!.remote && status.turn!!.remoteWaiting && !status.turn!!.waiting)
        assertEquals("remote", status.turn!!.via)
        assertTrue(store.state.value.remoteHere)  // the open conversation (chosen from the list) has it on
        assertTrue(store.remote(A, false))
        assertEquals("""{"type":"chat.remote","conversation":"$A","on":false}""", sent.last().toString())
        val user = ChatIn.event(j("""{"n":1,"at":1,"kind":"user","text":"from the phone","via":"remote"}""")) as ChatEvent.User
        assertEquals("remote", user.via)
        val tool = ChatIn.event(j("""{"n":2,"at":1,"kind":"tool","id":"t","name":"Bash","input":{},"via":"remote"}""")) as ChatEvent.Tool
        assertEquals("remote", tool.via)
    }
}
