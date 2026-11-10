// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.AgentInfo
import dev.zeffin.link.AgentsIn
import dev.zeffin.link.AppLink
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.AppProtocol
import dev.zeffin.link.ChatError
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatIn
import dev.zeffin.link.ChatOut
import dev.zeffin.link.ChatStore
import dev.zeffin.link.Effort
import dev.zeffin.link.LinkEvent
import dev.zeffin.link.Login
import dev.zeffin.link.Mode
import dev.zeffin.link.PermissionAnswer
import dev.zeffin.link.PhoneEvent
import dev.zeffin.link.RemoteCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentRemote

/**
 * The /app examples of docs/protocol/app/examples (test resources, see app/build.gradle.kts): the app reads every
 * message the server can send and builds the ones it sends the same way. The server's tests check the same examples
 * against the schemas, and that AppProtocol.kt has wire.py's names.
 */
class ProtocolExamplesTest {
    private fun examples(folder: String): Map<String, JsonObject> {
        val dir = File(javaClass.classLoader!!.getResource("app/examples/$folder")!!.toURI())
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
            .associate { it.name.removeSuffix(".json") to Json.parseToJsonElement(it.readText()).jsonObject }
    }

    private fun constants(holder: Any): Set<String> = holder.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }.toSet()

    private val server = examples("server")
    private val app = examples("app")
    private val events = examples("events")

    @Test fun everyExampleHasAKnownName() {
        assertTrue(server.size > 20 && app.size > 15 && events.size > 20)
        assertEquals(constants(AppProtocol.Out), server.values.map { it.str("type") }.toSet())
        assertEquals(constants(AppProtocol.In), app.values.map { it.str("type") }.toSet())
        assertEquals(constants(AppProtocol.Kind), events.values.map { it.str("kind") }.toSet())
        assertEquals(constants(AppProtocol.Notice),
            events.values.filter { it.str("kind") == AppProtocol.Kind.NOTICE }.map { it.str("code") }.toSet())
    }

    @Test fun noEventIsUnknown() {
        for ((name, o) in events) {
            val event = ChatIn.event(o)
            assertNotNull(name, event)
            assertFalse("$name is read as Unknown", event is ChatEvent.Unknown)
            assertEquals(name, o.int("n"), event!!.n)
        }
        val user = ChatIn.event(events.getValue("user.attachments")) as ChatEvent.User
        assertEquals(2, user.attachments.size)
        val result = ChatIn.event(events.getValue("tool.result")) as ChatEvent.ToolResult
        assertEquals(1024, result.screen!!.data.size)
        assertEquals(AppProtocol.VIA_REMOTE, (ChatIn.event(events.getValue("user.remote")) as ChatEvent.User).via)
    }

    @Test fun theLinkReadsEveryServerMessage() {
        val link = AppLink(testHttp())
        val chat = mutableListOf<JsonObject>()
        val other = mutableListOf<JsonObject>()
        link.onEvents { e ->
            if (e is LinkEvent.Chat) chat += e.message
            if (e is LinkEvent.OtherError) other += e.message
        }
        for ((name, m) in server) {
            val before = chat.size + other.size
            link.handle(m.toString())
            val type = m.str("type")
            if (type.startsWith(AppProtocol.CHAT_PREFIX) || (type == AppProtocol.Out.ERROR && m["request"] != null)) {
                assertEquals("$name goes to the chat", before + 1, chat.size + other.size)
                assertEquals(m, chat.last())
            } else if (type == AppProtocol.Out.ERROR) {
                assertEquals("$name goes to the log", m, other.last())
            }
        }
        val s = link.state.value
        assertEquals(AppProtocol.PROTOCOL, s.protocol)  // read from the last hello
        assertEquals("my-server", s.serverName)
        assertNotNull(s.status)
        assertNotNull(link.frame.value)
        assertTrue(s.audit.isNotEmpty())
        assertTrue(s.phone.any { it.ev == AppProtocol.PhoneEv.PAIRED })
        assertNotNull(s.diag)
        assertNotNull(s.latencyMs)
    }

    @Test fun helloProtocolIsKept() {
        val link = AppLink(testHttp())
        assertNull(link.state.value.protocol)
        link.handle(server.getValue("hello").toString())
        assertEquals(1, link.state.value.protocol)
        assertEquals(setOf("chat", "attachments", "delete", "remote"), link.state.value.features)
        link.handle(server.getValue("hello").toMutableMap().apply { put("protocol", JsonPrimitive(2)) }.let(::JsonObject).toString())
        assertEquals(2, link.state.value.protocol)  // a newer server: kept for the app to compare, nothing else yet
    }

    @Test fun theChatReadsEveryChatMessage() {
        val store = ChatStore(send = { true })
        val hello = server.getValue("hello")
        store.onHello(setOf("chat", "attachments", "delete", "remote"), hello)
        val remote = store.state.value.remote
        assertEquals(RemoteCapability("Remote Control", "Anthropic", "elsewhere", attachments = true), remote)
        // an agent's remote without "attachments": none with Remote Control on
        val claudeRemote = hello.array("agents").first().obj("remote")!!
        assertFalse(ChatIn.capability(JsonObject(claudeRemote.filterKeys { k -> k != "attachments" }))!!.attachments)
        assertEquals(AgentInfo("Claude Code", "2.1.308"), store.state.value.agent)
        val agents = AgentsIn.agents(hello)
        assertEquals(listOf(AppProtocol.AgentId.CLAUDE_CODE, AppProtocol.AgentId.OPENCODE), agents.map { it.id })
        val opencode = agents[1]
        assertNull(opencode.remote)  // no remote access: the app never offers it
        assertTrue(opencode.models.all { it.images } && opencode.models.any { it.reason == AppProtocol.ModelReason.NOT_SIGNED_IN })
        val status = ChatIn.status(server.getValue("chat.status"))
        assertEquals(AppProtocol.AgentId.CLAUDE_CODE, status.settings.defaultAgent)
        assertEquals(Mode.KNOWN, status.modeOptions.map { it.mode })  // the default agent's, for today's screens
        assertEquals(Login("claude.ai", subscription = true), status.login)
        assertEquals(listOf(Mode.MANUAL, Mode.ACCEPT_EDITS, Mode.PLAN), status.forAgent(AppProtocol.AgentId.OPENCODE).modeOptions.map { it.mode })
        assertEquals("opencode/nemotron-3.5-super-vl-free", status.settings.agents.getValue(AppProtocol.AgentId.OPENCODE).model)
        val notReady = ChatIn.status(server.getValue("chat.status.not-ready"))
        assertEquals(Login(null, subscription = false), notReady.login)
        assertEquals("not_configured", notReady.agent(AppProtocol.AgentId.OPENCODE)!!.problem)
        for (m in server.filterKeys { it.startsWith("chat.") }.values) store.onMessage(m)  // nothing thrown or dropped
        val st = ChatIn.status(server.getValue("chat.status.waiting"))
        assertEquals(1, st.pending.size)
        assertTrue(st.turn!!.waiting)
        val remoteStatus = ChatIn.status(server.getValue("chat.status.remote"))
        assertTrue(remoteStatus.turn!!.remote && remoteStatus.turn!!.remoteWaiting)
        assertFalse(remoteStatus.remote!!.starting)
        val conversations = server.getValue("chat.conversations").array("items").mapNotNull(ChatIn::conversation)
        assertEquals(listOf(AppProtocol.AgentId.CLAUDE_CODE, AppProtocol.AgentId.OPENCODE), conversations.map { it.agent })
        val (_, history, more) = ChatIn.history(server.getValue("chat.history"))!!
        assertEquals(5, history.size)
        assertTrue(more)
        assertNotNull(ChatIn.delta(server.getValue("chat.delta")))
        for ((name, m) in server.filterKeys { it.startsWith("error.chat-") }) {
            val error = ChatIn.error(m)
            assertEquals(name, m.str("error"), error.code)
            store.onMessage(m)
            assertEquals(name, error, store.state.value.error)
        }
    }

    @Test fun theAppSendsWhatTheExamplesShow() {
        for ((name, m) in app) {
            // AppLink writes the few without a builder by hand: their names come from AppProtocol.In.
            val build = builders[m.str("type")] ?: { o -> buildJsonObject { put("type", o.str("type")) } }
            assertEquals(name, m, build(m))
        }
    }

    /** How the app builds each chat message, from an example's values. */
    private val builders: Map<String, (JsonObject) -> JsonObject> = mapOf(
        AppProtocol.In.CHAT_LIST to { _ -> ChatOut.list() },
        AppProtocol.In.CHAT_NEW to { m -> ChatOut.new(m.mode("mode"), m.opt("model"), m.effort("effort"), m.opt("agent")) },
        AppProtocol.In.CHAT_OPEN to { m -> ChatOut.open(m.str("conversation"), m.int("before"), m.int("after"), m.int("limit")!!) },
        AppProtocol.In.CHAT_SEND to { m ->
            ChatOut.send(m.str("conversation"), m.str("text"), m["attachments"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
        },
        AppProtocol.In.CHAT_INTERRUPT to { m -> ChatOut.interrupt(m.str("conversation")) },
        AppProtocol.In.CHAT_SET to { m -> ChatOut.set(m.str("conversation"), m.mode("mode"), m.opt("model"), m.effort("effort")) },
        AppProtocol.In.CHAT_PERMISSION_ANSWER to { m ->
            ChatOut.answer(m.str("id"), PermissionAnswer.entries.single { it.wire == m.str("answer") },
                m["rules"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
        },
        AppProtocol.In.CHAT_UPDATE to { m -> ChatOut.update(m.str("conversation"), m.opt("title"), m.bool("pinned"), m.bool("favorite")) },
        AppProtocol.In.CHAT_DELETE to { m -> ChatOut.delete(m.str("conversation")) },
        AppProtocol.In.CHAT_SETTINGS to { m ->
            m.opt("default_agent")?.let(ChatOut::defaultAgent)
                ?: ChatOut.settings(m.mode("default_mode"), m.opt("default_model"), m.effort("default_effort"), m.int("permission_timeout_s"),
                    m.opt("agent"))
        },
        AppProtocol.In.CHAT_REMOTE to { m -> ChatOut.remote(m.str("conversation"), m.bool("on")!!) },
        AppProtocol.In.GATE to { m ->
            // the app's own builder: AppLink.gate sends this; "via" is the Flipper remote's
            AppLink.gateMessage(m.str("action"), m.opt("via")?.also { assertEquals(AppProtocol.GateVia.REMOTE, it) })
        },
    )

    @Test fun everyCodeHasItsOwnWords() {
        val chat = PresentChat(Lang.EN)
        val remote = PresentRemote(Lang.EN, RemoteCapability("Remote Control", "Anthropic", "elsewhere"))
        // The first six go to the app's log, never to a banner; bad_request and unknown_type mean an app bug and are
        // worded with their code ("The server refused it (code)").
        val coded = setOf(AppProtocol.Err.BAD_JSON, AppProtocol.Err.BAD_MESSAGE, AppProtocol.Err.BAD_ACTION,
            AppProtocol.Err.NOT_CONNECTED, AppProtocol.Err.FLIPPER, AppProtocol.Err.UNKNOWN_TYPE, AppProtocol.Err.BAD_REQUEST)
        for (code in constants(AppProtocol.Err) - coded) {
            val text = chat.errorText(ChatError(code, null, "chat.send"), remote)
            assertNotEquals(code, chat.errorText(ChatError("not-a-code", null, "chat.send"), remote).replace("not-a-code", code), text)
        }
        val generic = chat.problemText("not-a-code")
        for (code in constants(AppProtocol.Problem)) {
            val text = remote.problemText(code) ?: chat.problemText(code)
            assertNotEquals(code, generic.replace("not-a-code", code), text)
        }
        for (code in constants(AppProtocol.Notice)) {
            if (code.startsWith("remote_")) continue  // PresentRemote words them: below
            assertNotEquals(code, code, chat.noticeText(code, null))
        }
        for (ev in constants(AppProtocol.PhoneEv)) {
            val link = AppLinkState(phone = listOf(PhoneEvent(1.0, ev, null, null)))
            for (lang in listOf(Lang.EN, Lang.ES)) assertNotEquals(ev, ev, Present(lang).logUi(1, emptyList(), link).lines.single().text)
        }
        assertEquals("App paired with a code", Present(Lang.EN).logUi(1, emptyList(),
            AppLinkState(phone = listOf(PhoneEvent(1.0, AppProtocol.PhoneEv.PAIRED, null, null)))).lines.single().text)
        for (reason in constants(AppProtocol.RemoteOff)) {
            val data = buildJsonObject { put("reason", reason) }
            val text = remote.notice(AppProtocol.Notice.REMOTE_OFF, data)
            if (reason == AppProtocol.RemoteOff.OFF || reason == AppProtocol.RemoteOff.DELETED) assertNull(reason, text)
            else assertNotNull(reason, text)
        }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.mode(k: String) = opt(k)?.let { Mode.of(it)!! }
    private fun JsonObject.effort(k: String) = opt(k)?.let { Effort.of(it)!! }
    private fun JsonObject.opt(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.array(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
}
