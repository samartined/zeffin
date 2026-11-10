// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.AppProtocol
import dev.zeffin.link.ChatIn
import dev.zeffin.link.ChatState
import dev.zeffin.link.ChatStatus
import dev.zeffin.link.ChatStore
import dev.zeffin.notify.PresentNotify
import dev.zeffin.notify.TurnNote
import dev.zeffin.ui.present.ChatAction
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentConversations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import dev.zeffin.ChatFixtures as C

private fun j(text: String): JsonObject = oneAgent(Json.parseToJsonElement(text).jsonObject)

/**
 * The models as the server lists them, with their availability; the neutral problem codes next to the old ones; and
 * no fallback for older servers.
 */
class ModelAvailabilityTest {
    /** The schema's example: seven models in Claude Code's order, Fable with usage credits and an unchecked [1m] row. */
    private val example: ChatStatus = ChatIn.status(j(File(javaClass.classLoader!!
        .getResource("app/examples/server/chat.status.json")!!.toURI()).readText()))

    private val en = PresentChat(Lang.EN, "Claude Code")
    private val es = PresentChat(Lang.ES, "Claude Code")

    private fun withModel(model: String, status: ChatStatus = example) =
        C.writing(C.conversation.copy(model = model)).copy(status = status.copy(turn = null))

    // ---------------------------------------------------------------- reading chat.status

    @Test fun eachModelComesWithItsAvailability() {
        assertEquals(listOf("opus", "sonnet", "fable", "haiku", "claude-haiku-4-5-20251001", "claude-opus-4-6", "claude-opus-4-6[1m]"),
            example.models.map { it.value })
        assertEquals(listOf(true, true, false, true, true, true, false), example.models.map { it.available })
        assertEquals(listOf(null, null, "usage_credits", null, null, null, "unchecked"), example.models.map { it.reason })
    }

    @Test fun aModelWithoutAvailableIsNotAvailable() {
        val status = ChatIn.status(j("""{"models":[{"value":"opus","label":"Opus","efforts":[],"auto":true},
            {"value":"sonnet","label":"Sonnet","efforts":[],"auto":true,"available":"yes","reason":null},
            {"value":"haiku","label":"Haiku","efforts":[],"auto":true,"available":true,"reason":"usage_credits"}]}"""))
        assertEquals(listOf(false, false, true), status.models.map { it.available })
        assertNull(status.models[2].reason)  // available: no reason, whatever came
    }

    @Test fun noDefaultModelIsNoDefaultModel() {
        assertNull(ChatIn.status(j("""{"settings":{"default_mode":"default"}}""")).settings.defaultModel)
        assertNull(ChatIn.status(j("""{}""")).settings.defaultModel)
    }

    @Test fun aConversationWithoutAModelIsDroppedAndAnEmptyOneIsNoModel() {
        assertNull(ChatIn.conversation(j("""{"id":"c1","mode":"default","state":"ready"}""")))
        assertNull(ChatIn.conversation(j("""{"id":"c1","mode":"default","model":7,"state":"ready"}""")))
        assertEquals("", ChatIn.conversation(j("""{"id":"c1","mode":"default","model":"","state":"ready"}"""))!!.model)
    }

    @Test fun withoutModeOptionsThereAreNoModes() {
        assertEquals(emptyList<Any>(), ChatIn.status(j("""{"modes":["default","acceptEdits","plan","auto"]}""")).modeOptions)
        assertEquals(emptyList<Any>(), ChatIn.status(j("""{"mode_options":[{"value":""},{"label":"x"},7]}""")).modeOptions)
    }

    // ---------------------------------------------------------------- the model list

    @Test fun theListIsTheServersInItsOrderWithItsLabels() {
        val list = en.chatUi(withModel("opus"), emptyMap()).settings!!.models
        assertEquals(listOf("Opus 5.5", "Sonnet 5.5", "Fable 5.1", "Haiku 5.5", "Haiku 4.5", "Opus 4.6", "Opus 4.6 (1M context)"),
            list.map { it.title })
        assertEquals(listOf(true, false, false, false, false, false, false), list.map { it.selected })
        // labels only: no words of the app's own for an available model
        assertTrue(list.filter { it.enabled }.all { it.desc == null })
        // the Agent screen's sheet is the same list
        assertEquals(list.map { it.title to it.enabled }, en.modelOptions(example, "opus").map { it.title to it.enabled })
    }

    @Test fun anUnavailableModelIsShownWithItsReasonAndCannotBeChosen() {
        val list = en.modelOptions(example, "opus")
        assertEquals(listOf(true, true, false, true, true, true, false), list.map { it.enabled })
        assertEquals("Not available: it would bill usage credits, outside your plan.", list[2].desc)
        assertEquals("Not available: the server cannot confirm your plan includes it.", list[6].desc)
        val esList = es.modelOptions(example, "opus")
        assertEquals("No disponible: cobraría créditos de uso, fuera de tu plan.", esList[2].desc)
        assertEquals("No disponible: el servidor no puede confirmar que tu plan lo incluya.", esList[6].desc)
        val other = example.copy(models = listOf(example.models[0].copy(available = false, reason = "something_new"),
            example.models[1].copy(available = false, reason = null)))
        assertEquals(listOf("Not available on the server.", "Not available on the server."), en.modelOptions(other, null).map { it.desc })
        assertEquals("No disponible en el servidor.", es.modelOptions(other, null)[0].desc)
    }

    @Test fun fableIsAModelLikeAnyOther() {
        val available = example.copy(models = example.models.map { if (it.value == "fable") it.copy(available = true, reason = null) else it })
        val row = en.modelOptions(available, "opus")[2]
        assertEquals("Fable 5.1" to true, row.title to row.enabled)
        assertNull(row.desc)
        // chat.status's fable is not read: the model's own availability rules
        val status = ChatIn.status(j("""{"models":[{"value":"fable","label":"Fable 5.1","efforts":[],"auto":true,
            "available":false,"reason":"usage_credits"}],"fable":{"available":true,"reason":null}}"""))
        assertFalse(en.modelOptions(status, null).single().enabled)
    }

    @Test fun theNoteSaysTheAgentsNameOrTheAgent() {
        assertEquals("The models Claude Code lists, in its order. One your plan does not include is shown, but it cannot be chosen.",
            en.chatUi(withModel("opus"), emptyMap()).settings!!.modelNote)
        assertEquals("Los modelos que lista Claude Code, en su orden. Uno que tu plan no incluye aparece, pero no se puede elegir.",
            es.chatUi(withModel("opus"), emptyMap()).settings!!.modelNote)
        val nameless = withModel("opus").copy(agent = null)
        assertEquals("The models the agent lists, in its order. One your plan does not include is shown, but it cannot be chosen.",
            PresentChat(Lang.EN).chatUi(nameless, emptyMap()).settings!!.modelNote)
        assertEquals("The agent · this conversation · from your next message", PresentChat(Lang.EN).chatUi(nameless, emptyMap()).settings!!.sub)
        assertEquals("El agente · esta conversación · desde tu próximo mensaje", PresentChat(Lang.ES).chatUi(nameless, emptyMap()).settings!!.sub)
    }

    // ---------------------------------------------------------------- a conversation whose model is not available

    @Test fun aConversationOnAnUnavailableModelSaysSoAndWaits() {
        val ui = en.chatUi(withModel("fable"), emptyMap())
        assertEquals("Claude Code · Fable 5.1 · Medium · Manual", ui.settingsLine)
        assertEquals("This conversation's model is not available", ui.banner!!.title)
        assertEquals("Fable 5.1 would bill usage credits, outside your plan. Choose another model to write here.", ui.banner!!.text)
        assertEquals(listOf(ChatAction.CHOOSE_MODEL to "Choose a model"), ui.banner!!.actions)
        assertFalse(ui.composer.enabled)
        val esUi = es.chatUi(withModel("fable"), emptyMap())
        assertEquals("El modelo de esta conversación no está disponible", esUi.banner!!.title)
        assertEquals("Fable 5.1 cobraría créditos de uso, fuera de tu plan. Elige otro modelo para escribir aquí.", esUi.banner!!.text)
        assertEquals(listOf(ChatAction.CHOOSE_MODEL to "Elegir un modelo"), esUi.banner!!.actions)
        assertEquals("Opus 4.6 (1M context): the server cannot confirm your plan includes it. Choose another model to write here.",
            en.chatUi(withModel("claude-opus-4-6[1m]"), emptyMap()).banner!!.text)
        // an available model: no banner, the composer works
        val fine = en.chatUi(withModel("sonnet"), emptyMap())
        assertNull(fine.banner)
        assertTrue(fine.composer.enabled)
    }

    @Test fun aModelNoLongerListedOrNoneAtAllWaitsToo() {
        val gone = en.chatUi(withModel("claude-opus-4-1"), emptyMap())
        assertEquals("Claude Code · claude-opus-4-1 · Medium · Manual", gone.settingsLine)
        assertEquals("claude-opus-4-1 is no longer offered. Choose another model to write here.", gone.banner!!.text)
        assertFalse(gone.composer.enabled)
        assertEquals("claude-opus-4-1 ya no se ofrece. Elige otro modelo para escribir aquí.", es.chatUi(withModel("claude-opus-4-1"), emptyMap()).banner!!.text)
        val none = en.chatUi(withModel(""), emptyMap())
        assertEquals("Claude Code · — · Medium · Manual", none.settingsLine)
        assertEquals("This conversation has no model. Choose one to write here.", none.banner!!.text)
        assertEquals("Esta conversación no tiene modelo. Elige uno para escribir aquí.", es.chatUi(withModel(""), emptyMap()).banner!!.text)
        assertFalse(none.composer.enabled)
        // with no list from the server, nothing to judge by
        assertNull(en.chatUi(withModel("claude-opus-4-1", example.copy(models = emptyList())), emptyMap()).banner)
    }

    @Test fun noDefaultModelShowsADash() {
        val state = C.writing().copy(status = example.copy(settings = example.settings.copy(defaultModel = null)))
        assertEquals("—", en.agentUi(state).model)
    }

    // ---------------------------------------------------------------- the neutral codes

    @Test fun theNeutralCodesReadAsTheOldOnes() {
        assertEquals("agent_missing", AppProtocol.Problem.AGENT_MISSING)
        assertEquals("agent_failed", AppProtocol.TurnError.AGENT_FAILED)
        for (p in listOf(en, es, PresentChat(Lang.EN), PresentChat(Lang.ES, "Demo agent"))) {
            assertEquals(p.problemText("claude_missing"), p.problemText("agent_missing"))
            assertEquals(p.turnError("claude_failed"), p.turnError("agent_failed"))
        }
        assertEquals("Claude Code was not found on the server: run zf agent setup again.", en.problemText("agent_missing"))
        assertEquals("Claude Code failed.", en.turnError("agent_failed"))
        val note = TurnNote(C.ID, "error", "agent_failed", null)
        assertEquals(PresentNotify(Lang.EN).turnEnded(note.copy(error = "claude_failed"), "x").text,
            PresentNotify(Lang.EN).turnEnded(note, "x").text)
        assertEquals("The agent failed.", PresentNotify(Lang.EN).turnEnded(note, "x").text)
    }

    @Test fun theSignInTextNamesNoCommandOfClaudeCode() {
        assertEquals("Claude Code is not signed in on the server: sign it in there, then run zf restart.", en.problemText("not_logged_in"))
        assertEquals("Claude Code no tiene la sesión iniciada en el servidor: iníciala allí y luego ejecuta zf restart.",
            es.problemText("not_logged_in"))
    }

    // ---------------------------------------------------------------- no name for an older server

    @Test fun withoutHellosAgentTheTextsSayTheAgent() {
        val store = ChatStore(send = { true })
        store.onHello(setOf("chat"))
        assertNull(store.state.value.agentName)
        val state: ChatState = C.writing().copy(agent = null)
        val words = listOf(PresentChat(Lang.EN, state.agentName), PresentChat(Lang.ES, state.agentName))
        val texts = words.flatMap { p ->
            listOf(p.problemText("agent_missing"), p.problemText("checking"), p.problemText("not_logged_in"),
                p.problemText("not_subscription"), p.turnError("expired"), p.turnError("agent_failed"),
                p.modeDesc(dev.zeffin.link.Mode.AUTO)!!, p.effortDesc(dev.zeffin.link.Effort.MEDIUM),
                p.errorText(dev.zeffin.link.ChatError("expired", null, null))) +
                p.chatUi(state, emptyMap()).settings!!.let { listOf(it.sub, it.note, it.modeNote, it.modelNote) } +
                p.agentUi(state).name
        } + PresentConversations(Lang.EN, state.agentName).deleteText(C.conversation, named = false, busy = false) +
            PresentConversations(Lang.ES, state.agentName).deleteText(C.conversation, named = false, busy = false)
        assertEquals(emptyList<String>(), texts.filter { "Claude" in it })
        assertEquals("The agent was not found on the server: run zf agent setup again.", words[0].problemText("agent_missing"))
        assertEquals("The server is checking the agent; one moment.", words[0].problemText("checking"))
        assertEquals("The agent failed.", words[0].turnError("agent_failed"))
        assertEquals("El agente falló.", words[1].turnError("agent_failed"))
        assertEquals("Lo ejecuta todo, con las comprobaciones de seguridad del agente; muestra lo que bloquea.",
            words[1].modeDesc(dev.zeffin.link.Mode.AUTO))
        assertEquals("The agent's own settings. Flipper tools never ask in any mode, and Pause agent still rules.",
            words[0].chatUi(state, emptyMap()).settings!!.note)
        assertEquals("Los ajustes propios del agente. Las herramientas del Flipper nunca preguntan en ningún modo, y Pausar sigue mandando.",
            words[1].chatUi(state, emptyMap()).settings!!.note)
        assertTrue(PresentConversations(Lang.EN, null).deleteText(C.conversation, named = false, busy = false).contains("(the agent's transcript)"))
        assertTrue(PresentConversations(Lang.ES, null).deleteText(C.conversation, named = false, busy = false).contains("(la transcripción del agente)"))
    }
}
