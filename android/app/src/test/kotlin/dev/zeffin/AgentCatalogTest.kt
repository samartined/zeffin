// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.AgentInfo
import dev.zeffin.link.ChatIn
import dev.zeffin.link.ChatOut
import dev.zeffin.link.ChatState
import dev.zeffin.link.ChatStore
import dev.zeffin.link.Mode
import dev.zeffin.link.ModeOption
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentConversations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.zeffin.ChatFixtures as C

private fun j(text: String): JsonObject = oneAgent(Json.parseToJsonElement(text).jsonObject)

/** The agent's catalog as data on /app, so the app knows no agent: its name, modes and models. */
class AgentCatalogTest {
    private val withOptions = ChatIn.status(j("""{"type":"chat.status","ready":true,"problem":null,
        "login":{"method":"claude.ai","subscription":true},
        "models":[{"value":"opus","label":"Opus","efforts":["low","medium"],"auto":true,"available":true,"reason":null},
                  {"value":"opus-next","label":"Opus Next","efforts":[],"auto":true,"available":true,"reason":null}],
        "modes":["default","acceptEdits","plan","auto"],
        "mode_options":[{"value":"plan","label":"Plan"},{"value":"dontAsk","label":"Don't ask"},
                        {"value":"default","label":"Manual"},{"value":"acceptEdits","label":"Accept edits"}],
        "settings":{"default_mode":"dontAsk","default_model":"opus","default_effort":"medium","permission_timeout_s":120},
        "turn":null,"pending":[],"remote":null}"""))

    @Test fun theServersHomeComesFromHello() {
        val store = ChatStore(send = { true })
        store.onHello(setOf("chat"), j("""{"type":"hello","server":{"version":"1.0.0","name":"my-server","home":"/home/demo"}}"""))
        assertEquals("/home/demo", store.state.value.home)
        store.onHello(setOf("chat"), j("""{"type":"hello","server":{"version":"0.15.1","name":"my-server"}}"""))  // an older server
        assertNull(store.state.value.home)
    }

    @Test fun theAgentsNameComesFromHelloAndWithoutItThereIsNone() {
        val store = ChatStore(send = { true })
        store.onHello(setOf("chat"))
        assertNull(store.state.value.agent)
        assertNull(store.state.value.agentName)
        // as an older server sent it (not made one agent's entry): no agents, so nothing names one
        store.onMessage(Json.parseToJsonElement("""{"type":"chat.status","ready":true,"problem":null,
            "claude":{"version":"2.1.308","auth":"claude.ai"}}""").jsonObject)
        assertNull(store.state.value.agentVersion)  // an older server: chat.status's claude is not read
        fun hello(agent: String) = j("""{"type":"hello","agents":[{"id":"demo",$agent}]}""")
        store.onHello(setOf("chat"), hello(""""name":"Demo agent","version":"1.2.3""""))
        assertEquals(AgentInfo("Demo agent", "1.2.3"), store.state.value.agent)
        assertEquals("1.2.3", store.state.value.agentVersion)
        store.onHello(setOf("chat"), hello(""""name":"Demo agent","version":null"""))  // still being checked
        assertNull(store.state.value.agentVersion)
        for (bad in listOf(""""version":"1"""", """"name":"  """", """"name":7""")) {
            store.onHello(setOf("chat"), hello(bad))
            assertEquals(bad, "demo", store.state.value.agentName)  // no usable name: its id names it
        }
        store.onHello(setOf("chat"), j("""{"type":"hello","agents":[{"id":"Not An Id","name":"Demo agent"}]}"""))
        assertNull(store.state.value.agentName)  // an entry without a usable id is dropped
    }

    @Test fun theAgentScreenShowsItsNameAndVersion() {
        val state = C.writing().copy(agent = AgentInfo("Demo agent", "1.2.3"))
        val ui = PresentChat(Lang.EN, state.agentName).agentUi(state)
        assertEquals("Demo agent", ui.name)
        assertEquals("1.2.3", ui.version)
        assertEquals("Demo agent 1.2.3 · Opus · Manual", PresentChat(Lang.EN, state.agentName).agentLine(state)!!.rest)
        // the examples' hello
        val examples = PresentChat(Lang.EN, C.writing().agentName)
        assertEquals("Claude Code", examples.agentUi(C.writing()).name)
        assertEquals("2.1.308", examples.agentUi(C.writing()).version)
        assertEquals("Claude Code 2.1.308 · Opus · Manual", examples.agentLine(C.writing())!!.rest)
        // an older server, without hello's agent: no version
        assertEquals("—", PresentChat(Lang.EN).agentUi(C.writing().copy(agent = null)).version)
    }

    @Test fun theLoginLineComesFromLogin() {
        fun login(login: String?) = ChatState(supported = true, connected = true,
            status = ChatIn.status(j("""{"type":"chat.status","ready":true,"problem":null${login?.let { ",\"login\":$it" } ?: ""}}""")))
        val en = PresentChat(Lang.EN)
        val es = PresentChat(Lang.ES)
        val sub = login("""{"method":"demo-sso","subscription":true}""")
        assertEquals("Subscription (demo-sso)", en.agentUi(sub).login)
        assertEquals("Suscripción (demo-sso)", es.agentUi(sub).login)
        assertEquals("Demo login", en.agentUi(login("""{"method":"Demo login","subscription":false}""")).login)
        assertEquals("—", en.agentUi(login("""{"method":null,"subscription":false}""")).login)
        assertEquals("—", en.agentUi(login("""{"method":null,"subscription":true}""")).login)
        assertEquals("—", en.agentUi(login(null)).login)  // an older server, without login
    }

    @Test fun theTextsAboutTheAgentSayItsName() {
        val en = PresentChat(Lang.EN, "Claude Code")
        val es = PresentChat(Lang.ES, "Claude Code")
        // Claude Code, word for word as before (the captures stay as they are)
        assertEquals("The server is checking Claude Code; one moment.", en.problemText("checking"))
        assertEquals("Claude Code was not found on the server: run zf agent setup again.", en.problemText("claude_missing"))
        assertEquals("Claude Code no tiene la sesión iniciada en el servidor: iníciala allí y luego ejecuta zf restart.",
            es.problemText("not_logged_in"))
        assertEquals("Claude Code does not use a subscription login, so the chat is refused.", en.problemText("not_subscription"))
        assertEquals("Claude Code no longer has this conversation.", en.turnError("expired"))
        assertEquals("Runs everything, with Claude Code's background safety checks; shows what it blocks.", en.modeDesc(Mode.AUTO))
        assertEquals("Equilibrado: donde Claude Code arranca estos modelos.", es.effortDesc(dev.zeffin.link.Effort.MEDIUM))
        val settings = en.chatUi(C.writing(), emptyMap()).settings!!
        assertEquals("Claude Code · this conversation · from your next message", settings.sub)
        assertEquals("The same modes as Claude Code.", settings.modeNote)
        assertEquals("Claude Code's own settings. Flipper tools never ask in any mode, and Pause agent still rules.", settings.note)
        // another agent
        val other = PresentChat(Lang.EN, "Demo agent")
        val otherEs = PresentChat(Lang.ES, "Demo agent")
        assertEquals("The server is checking Demo agent; one moment.", other.problemText("checking"))
        assertEquals("No se encuentra Demo agent en el servidor: vuelve a ejecutar zf agent setup.", otherEs.problemText("claude_missing"))
        assertEquals("Demo agent is not signed in on the server: sign it in there, then run zf restart.", other.problemText("not_logged_in"))
        assertEquals("Demo agent does not use a subscription login, so the chat is refused.", other.problemText("not_subscription"))
        assertEquals("Demo agent no longer has this conversation.", other.turnError("expired"))
        assertEquals("Demo agent falló.", otherEs.turnError("claude_failed"))
        assertTrue(other.errorText(dev.zeffin.link.ChatError("expired", null, null)).startsWith("Demo agent no longer"))
        assertEquals("Runs everything, with Demo agent's background safety checks; shows what it blocks.", other.modeDesc(Mode.AUTO))
        assertEquals("Balanced: where Demo agent starts these models.", other.effortDesc(dev.zeffin.link.Effort.MEDIUM))
        val otherSettings = otherEs.chatUi(C.writing(), emptyMap()).settings!!
        assertEquals("Demo agent · esta conversación · desde tu próximo mensaje", otherSettings.sub)
        assertEquals("Los mismos modos que Demo agent.", otherSettings.modeNote)
        assertTrue(otherSettings.modelNote.startsWith("Los modelos que lista Demo agent, en su orden; uno de pago lo dice."))
        assertTrue(PresentConversations(Lang.EN, "Demo agent").deleteText(C.conversation, named = false, busy = false)
            .contains("(Demo agent's transcript)"))
        assertTrue(PresentConversations(Lang.EN, "Claude Code").deleteText(C.conversation, named = false, busy = false)
            .contains("(Claude Code's transcript)"))
    }

    @Test fun theFourModesTheAppKnowsKeepItsWords() {
        val status = ChatIn.status(j("""{"type":"chat.status","ready":true,"problem":null,"mode_options":[{"value":"default","label":"Manual"},
            {"value":"acceptEdits","label":"Accept edits"},{"value":"plan","label":"Plan"},{"value":"auto","label":"Auto"}]}"""))
        assertEquals(C.modeOptions, status.modeOptions)
        assertEquals(listOf("Manual" to true, "Accept edits" to false, "Plan" to false, "Auto" to false),
            PresentChat(Lang.EN).chatUi(C.writing(), emptyMap()).settings!!.modes.map { it.title to it.selected })
        assertEquals(listOf("Manual", "Aceptar ediciones", "Plan", "Automático"),
            PresentChat(Lang.ES).modeOptions(status, Mode.MANUAL).map { it.title })
        // none usable: none (no fallback for an older server)
        assertEquals(emptyList<ModeOption>(), ChatIn.status(j("""{"mode_options":[{"value":""},{"label":"x"},7,{"value":"has space"}]}""")).modeOptions)
    }

    @Test fun theModeSheetListsTheAgentsModesInItsOrder() {
        assertEquals(listOf("plan", "dontAsk", "default", "acceptEdits"), withOptions.modeOptions.map { it.mode.wire })
        val es = PresentChat(Lang.ES).modeOptions(withOptions, Mode("dontAsk"))
        assertEquals(listOf("Plan", "Don't ask", "Manual", "Aceptar ediciones"), es.map { it.title })
        assertEquals(listOf(false, true, false, false), es.map { it.selected })
        assertNull(es[1].desc)  // an unknown mode: by its label, with no hint
        assertTrue(es[0].desc!!.startsWith("Lee y propone un plan"))
        // it can be chosen: its value goes as it came
        assertEquals("dontAsk", ChatOut.set("c1", mode = es[1].value)["mode"]!!.jsonPrimitive.content)
        assertEquals("dontAsk", ChatOut.settings(mode = es[1].value)["default_mode"]!!.jsonPrimitive.content)
        // the default and the Agent screen by its label
        assertEquals(Mode("dontAsk"), withOptions.settings.defaultMode)
        val state = C.writing().copy(status = withOptions)
        assertEquals("Don't ask", PresentChat(Lang.EN).agentUi(state).mode)
    }

    @Test fun aConversationInAModeTheAppDoesNotKnowStillOpens() {
        val c = ChatIn.conversation(j("""{"id":"c1","mode":"dontAsk","model":"opus","state":"ready"}"""))!!
        assertEquals(Mode("dontAsk"), c.mode)
        val known = C.writing().copy(status = withOptions, conversations = listOf(C.conversation.copy(mode = c.mode)))
        val ui = PresentChat(Lang.EN).chatUi(known, emptyMap())
        assertEquals("Don't ask", ui.settings!!.modeLabel)
        assertEquals("Saved infrared remotes", ui.title)
        assertTrue(ui.items.isNotEmpty())
        // not among the agent's options either: by its value
        val unlisted = known.copy(conversations = listOf(C.conversation.copy(mode = Mode("bypassPermissions"))))
        assertEquals("Opus · Medium · bypassPermissions", PresentChat(Lang.EN).chatUi(unlisted, emptyMap()).settingsLine)
        assertTrue(PresentChat(Lang.EN).chatUi(unlisted, emptyMap()).settings!!.modes.none { it.selected })
    }

    @Test fun anUnknownModelShowsByItsLabel() {
        val en = PresentChat(Lang.EN)
        assertEquals("Opus Next", en.modelName("opus-next", withOptions))
        assertEquals("Opus", en.modelName("opus", withOptions))
        assertEquals("sonnet", en.modelName("sonnet", withOptions))  // not listed: its value, as it is
        val state = C.writing().copy(status = withOptions, conversations = listOf(C.conversation.copy(model = "opus-next", effort = null)))
        val ui = en.chatUi(state, emptyMap())
        assertEquals("Opus Next", ui.settings!!.modelLabel)
        assertEquals(listOf("Opus", "Opus Next"), ui.settings!!.models.map { it.title })
    }
}
