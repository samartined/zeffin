// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.AgentInfo
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatIn
import dev.zeffin.link.ChatStore
import dev.zeffin.link.ConvState
import dev.zeffin.link.Effort
import dev.zeffin.link.Login
import dev.zeffin.link.Mode
import dev.zeffin.ui.present.PresentChat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

/**
 * Seeded random /app messages for [ChatIn]: valid ones keep their values, and mutated ones (fields missing, of the
 * wrong type, with unknown values or extra fields) never throw, through the parser, the store and the chat's words.
 * The seed is fixed, so a failure repeats; it prints the message that broke.
 */
class ChatProtocolRandomTest {
    private val rnd = Random(SEED)

    // ------------------------------------------------------------------ valid messages
    private fun word(max: Int = 12) = (1..rnd.nextInt(1, max + 1)).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
    private fun name() = word() + (if (rnd.nextBoolean()) " " + word(6) else "")
    private fun mode() = if (rnd.nextBoolean()) Mode.KNOWN.random(rnd) else Mode("x" + word(10))
    private fun effort() = Effort.entries.random(rnd)
    private fun p(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::p))
        else -> error("$v")
    }
    private fun o(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to p(v) })

    private data class Status(val version: String, val models: List<Pair<String, String>>, val modes: List<Pair<Mode, String>>,
                              val defaultMode: Mode, val effort: Effort, val timeout: Int, val turn: Triple<String, Int, Mode>?)

    private fun status(): Pair<Status, JsonObject> {
        val models = (1..rnd.nextInt(1, 5)).map { "m-" + word(8) to name() }.distinctBy { it.first }
        val modes = (1..rnd.nextInt(1, 7)).map { mode() to name() }.distinctBy { it.first }
        val s = Status(word(3) + ".1.2", models, modes, modes.random(rnd).first, effort(), rnd.nextInt(30, 600),
            if (rnd.nextBoolean()) Triple("c-" + word(8), rnd.nextInt(1, 99), mode()) else null)
        val json = o("type" to "chat.status", "ready" to true, "problem" to null,
            "login" to o("method" to s.version, "subscription" to true),
            "models" to s.models.map { (v, l) -> o("value" to v, "label" to l, "efforts" to listOf("low", "high"), "auto" to true) },
            "fable" to o("available" to false, "reason" to "not_offered"),
            "modes" to listOf("default", "acceptEdits", "plan", "auto"),
            "mode_options" to s.modes.map { (m, l) -> o("value" to m.wire, "label" to l) },
            "settings" to o("default_mode" to s.defaultMode.wire, "default_model" to s.models[0].first,
                "default_effort" to s.effort.wire, "permission_timeout_s" to s.timeout),
            "turn" to s.turn?.let { (c, n, m) -> o("conversation" to c, "turn" to n, "mode" to m.wire, "model" to "opus",
                "effort" to null, "started_at" to 1.5, "waiting" to false) },
            "pending" to emptyList<Any>(), "remote" to null)
        return s to oneAgent(json)
    }

    private fun conversation(id: String = "c-" + word(8)) = o("id" to id, "created" to 1.0, "updated" to rnd.nextDouble(2.0, 9.0),
        "title" to name(), "last" to name(), "pinned" to rnd.nextBoolean(), "favorite" to false, "flipper" to true,
        "mode" to mode().wire, "model" to "m-" + word(6), "effort" to effort().wire, "state" to "ready",
        "turns" to rnd.nextInt(0, 50), "events" to rnd.nextInt(0, 500), "attachments" to 0, "remote" to false)

    private fun event(n: Int): JsonObject = when (rnd.nextInt(6)) {
        0 -> o("n" to n, "at" to 1.0, "kind" to "user", "text" to name(), "attachments" to emptyList<Any>())
        1 -> o("n" to n, "at" to 1.0, "kind" to "turn.start", "turn" to n, "mode" to mode().wire, "model" to "opus", "effort" to "low")
        2 -> o("n" to n, "at" to 1.0, "kind" to "text", "id" to "b$n", "text" to name())
        3 -> o("n" to n, "at" to 1.0, "kind" to "tool", "id" to "t$n", "name" to "mcp__flipper__flipper_press",
            "input" to o("keys" to listOf("OK")))
        4 -> o("n" to n, "at" to 1.0, "kind" to "permission", "id" to "p$n", "tool" to "Bash", "input" to o("command" to name()),
            "deadline" to 9.0, "always" to true, "rules" to listOf("Bash(" + word() + ")"))
        else -> o("n" to n, "at" to 1.0, "kind" to "turn.end", "turn" to n, "outcome" to "done", "error" to null, "duration_ms" to 10)
    }

    private fun anyMessage(): JsonObject = when (rnd.nextInt(7)) {
        0 -> status().second
        1 -> o("type" to "chat.conversations", "items" to (1..rnd.nextInt(0, 4)).map { conversation() })
        2 -> o("type" to "chat.conversation", "item" to conversation())
        3 -> o("type" to "chat.history", "conversation" to "c1", "events" to (1..rnd.nextInt(0, 6)).map(::event), "more" to false)
        4 -> o("type" to "chat.event", "conversation" to "c1").let { JsonObject(it + event(rnd.nextInt(1, 99))) }
        5 -> o("type" to "chat.delta", "conversation" to "c1", "turn" to 1, "id" to "b1", "offset" to 0, "text" to name())
        else -> o("type" to "error", "error" to listOf("busy", "bad_mode", "expired", word()).random(rnd), "data" to o())
    }

    // ------------------------------------------------------------------ mutations
    private fun junk(): JsonElement = when (rnd.nextInt(8)) {
        0 -> JsonNull
        1 -> JsonPrimitive(rnd.nextInt())
        2 -> JsonPrimitive(rnd.nextDouble() * 1e12)
        3 -> JsonPrimitive(rnd.nextBoolean())
        4 -> JsonPrimitive(word(20) + "\u0000‮\n")
        5 -> JsonArray(listOf(JsonPrimitive(1), JsonNull, JsonPrimitive("x")))
        6 -> JsonObject(mapOf("value" to JsonPrimitive(word()), "n" to JsonPrimitive("7")))
        else -> JsonPrimitive("x".repeat(rnd.nextInt(100, 5000)))
    }

    private fun mutate(e: JsonElement, depth: Int = 0): JsonElement = when {
        depth > 0 && rnd.nextInt(10) == 0 -> junk()
        e is JsonObject -> {
            val m = e.mapValues { (_, v) -> mutate(v, depth + 1) }.toMutableMap()
            if (m.isNotEmpty() && rnd.nextInt(4) == 0) m.remove(m.keys.random(rnd))                // a field missing
            if (rnd.nextInt(5) == 0) m[word()] = junk()                                             // an extra field
            if (m.isNotEmpty() && rnd.nextInt(6) == 0) m[m.keys.random(rnd)] = JsonPrimitive(word()) // an unknown value
            JsonObject(m)
        }
        e is JsonArray -> JsonArray(e.map { mutate(it, depth + 1) } + (if (rnd.nextInt(6) == 0) listOf(junk()) else emptyList()))
        else -> e
    }

    // ------------------------------------------------------------------ the tests
    @Test fun aValidStatusKeepsItsValues() = repeat(RUNS) {
        val (s, json) = status()
        val st = ChatIn.status(json)
        assertEquals("$json", Login(s.version, subscription = true), st.login)
        assertEquals("$json", s.models, st.models.map { it.value to it.label })
        assertEquals("$json", s.modes, st.modeOptions.map { it.mode to it.label })
        assertEquals("$json", s.defaultMode, st.settings.defaultMode)
        assertEquals("$json", s.effort, st.settings.defaultEffort)
        assertEquals("$json", s.timeout, st.settings.permissionTimeoutS)
        assertEquals("$json", s.turn, st.turn?.let { Triple(it.conversation, it.turn, it.mode) })
        for ((mode, label) in s.modes) {
            val shown = PresentChat(Lang.EN).modeName(mode, st)
            if (mode !in Mode.KNOWN) assertEquals("$json", label, shown)
        }
    }

    @Test fun aValidConversationAndEventKeepTheirValues() = repeat(RUNS) {
        val json = conversation()
        val c = ChatIn.conversation(json)!!
        fun str(k: String) = (json[k] as JsonPrimitive).content
        assertEquals("$json", listOf(str("id"), str("title"), str("last"), str("model")), listOf(c.id, c.title, c.last, c.model))
        assertEquals("$json", Mode(str("mode")), c.mode)
        assertEquals("$json", Effort.of(str("effort")), c.effort)
        assertEquals("$json", ConvState.READY, c.state)
        val n = rnd.nextInt(1, 1000)
        val e = event(n)
        val parsed = ChatIn.event(e)!!
        assertEquals("$e", n, parsed.n)
        when (parsed) {
            is ChatEvent.User -> assertEquals("$e", str2(e, "text"), parsed.text)
            is ChatEvent.TurnStart -> assertEquals("$e", Mode(str2(e, "mode")), parsed.mode)
            is ChatEvent.Text -> assertEquals("$e", str2(e, "text"), parsed.text)
            is ChatEvent.Tool -> assertEquals("$e", "mcp__flipper__flipper_press", parsed.name)
            is ChatEvent.Permission -> assertEquals("$e", "Bash", parsed.tool)
            is ChatEvent.TurnEnd -> assertEquals("$e", "done", parsed.outcome)
            else -> error("unexpected $parsed from $e")
        }
    }

    @Test fun aValidAgentKeepsItsValues() = repeat(RUNS) {
        val name = name()
        val version = if (rnd.nextBoolean()) word(3) + ".0" else null
        assertEquals(AgentInfo(name, version), ChatIn.agent(o("name" to name, "version" to version)))
    }

    @Test fun mutatedMessagesNeverThrow() {
        val store = ChatStore(send = { true })
        val words = listOf(PresentChat(Lang.EN), PresentChat(Lang.ES, "Demo agent"))
        repeat(RUNS * 4) {
            val m = mutate(anyMessage()) as JsonObject
            try {
                ChatIn.status(m)
                ChatIn.conversation(m)
                ChatIn.event(m)
                ChatIn.history(m)
                ChatIn.delta(m)
                ChatIn.error(m)
                ChatIn.agent(m)
                ChatIn.capability(m)
                store.onHello(setOf("chat", "remote"), mutate(o("agents" to listOf(o("id" to "claude-code", "name" to name(),
                    "remote" to o("name" to "Remote Control"))))) as? JsonObject)
                store.onMessage(m)
                (m["item"] as? JsonObject)?.let { store.onMessage(JsonObject(mapOf("type" to JsonPrimitive("chat.conversation"), "item" to it))) }
                for (w in words) {
                    w.chatUi(store.state.value, emptyMap())
                    w.agentUi(store.state.value)
                    w.lastConversation(store.state.value)
                }
            } catch (e: Exception) {
                throw AssertionError("threw on $m", e)
            }
        }
        assertNotNull(store.state.value.status)
    }

    private fun str2(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content

    private companion object {
        const val SEED = 20_261_027L
        const val RUNS = 500
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}
