// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.ChatIn
import dev.zeffin.link.ChatStore
import dev.zeffin.link.ModelInfo
import dev.zeffin.ui.present.AgentWords
import dev.zeffin.ui.present.PresentAgentChoice
import dev.zeffin.ui.present.PresentAgents
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.Tone
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

private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/**
 * The "free only" switch, as the app words it (paid usage is the user's choice): a paid model says so, and the texts
 * promise "free" only while the switch is on.
 */
class FreeOnlyTextsTest {
    private val en = PresentChat(Lang.EN, "OpenCode")
    private val es = PresentChat(Lang.ES, "OpenCode")
    private fun example(name: String) = ChatIn.status(j(File(javaClass.classLoader!!
        .getResource("app/examples/server/$name.json")!!.toURI()).readText()))

    @Test fun theSwitchAndPaidAreRead() {
        val paid = example("chat.status.paid")
        assertFalse(paid.freeOnly)
        val open = paid.agents.first { it.id == "opencode" }
        assertEquals(listOf(true, false), open.models.map { it.paid })  // OpenRouter's first, then Zen's free one
        assertEquals("OpenRouter, OpenCode Zen", open.login!!.method)
        assertTrue(example("chat.status").freeOnly)
        assertFalse(ChatIn.status(j("""{"agents":[]}""")).freeOnly)  // a server before 1.0.0: off
        val store = ChatStore(send = { true })
        store.onHello(setOf("chat"), j("""{"type":"hello","free_only":true,"agents":[]}"""))
        assertTrue(store.state.value.freeOnly)
        store.onHello(setOf("chat"), j("""{"type":"hello","agents":[]}"""))
        assertFalse(store.state.value.freeOnly)
    }

    @Test fun aPaidModelSaysSoNextToWhetherItTakesImages() {
        val state = C.withPaidModel()
        val rows = en.modelOptions(state.status, null)
        val paid = rows[rows.size - 2]
        assertEquals("Coder Large", paid.title)
        assertEquals(Tone.WARNING to "Paid · Text only", paid.lead)
        assertEquals(Tone.SUCCESS to "Takes images", rows.first().lead)
        assertEquals("De pago · Solo texto", es.modelOptions(state.status, null)[rows.size - 2].lead!!.second)
        // alone, without the images' word
        val status = state.status!!.copy(models = listOf(ModelInfo("p/x", "X", emptyList(), false, paid = true)))
        assertEquals(Tone.WARNING to "Paid", en.modelOptions(status, null).single().lead)
        assertNull(en.modelOptions(status.copy(models = listOf(ModelInfo("p/x", "X", emptyList(), false))), null).single().lead)
    }

    @Test fun theModelNoteAndReasonsPromiseFreeOnlyWithTheSwitchOn() {
        val off = C.withOpenCode().status!!
        val on = off.copy(freeOnly = true)
        assertTrue(en.modelNote(off, providers = true).startsWith(
            "The models OpenCode lists, in its order; a paid one says so. One it cannot use is shown, but it cannot be chosen."))
        assertTrue(en.modelNote(on, providers = true).startsWith(
            "The free models OpenCode lists, in its order. One it cannot use is shown, but it cannot be chosen."))
        assertTrue(en.modelNote(on, providers = false).contains("One your plan does not include"))
        // Off, the server sends no row for a provider nobody signed in; one that comes anyway is worded neutrally
        assertTrue(off.models.all { it.available })
        val signedOut = ModelInfo("nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b", "Nemotron 3.5 Super VL", emptyList(), false,
            available = false, reason = "not_signed_in")
        val offRow = off.copy(models = listOf(signedOut))
        val onRow = on.copy(models = listOf(signedOut))
        assertEquals("Not available: the agent is not signed in to its provider.", en.modelOptions(offRow, null).last().desc)
        assertEquals("Not available: free, but the agent is not signed in to its provider.", en.modelOptions(onRow, null).last().desc)
        assertEquals("No disponible: el agente no tiene la sesión iniciada en su proveedor.", es.modelOptions(offRow, null).last().desc)
    }

    @Test fun theAgentsPagesPromiseFreeOnlyWithTheSwitchOn() {
        val pages = PresentAgents(Lang.EN)
        val off = pages.info(C.openCode)
        assertEquals(listOf("Version", "Providers"), off.rows.map { it.first })
        assertTrue(off.note!!.startsWith("The providers signed in on the server, free or paid."))
        assertTrue(off.privacy!!.startsWith("Free providers keep what they receive"))  // the line stays, for free providers
        val on = pages.info(C.openCode, freeOnly = true)
        assertEquals(listOf("Version", "Free providers"), on.rows.map { it.first })
        assertTrue(on.note!!.startsWith("Only free providers and models whose price is zero."))
        assertTrue(pages.info(C.claudeCode).note!!.startsWith("With the login set up on the server."))
        assertTrue(pages.info(C.claudeCode, freeOnly = true).note!!.startsWith("Only with your subscription"))
        assertNull(pages.info(C.claudeCode).privacy)
        assertEquals("No provider is signed in. On the server, sign OpenCode in, then run zf restart. zf agent setup --agent opencode says how.",
            AgentWords(Lang.EN).reason(C.openCodeSignedOut))
        assertTrue(AgentWords(Lang.EN, freeOnly = true).reason(C.openCodeSignedOut)!!.startsWith("No free provider is signed in."))
        assertEquals("Its settings on the server could make it leak. Run zf agent setup --agent opencode there to write them again.",
            AgentWords(Lang.EN).reason(C.openCode.copy(ready = false, problem = "unsafe_agent_settings")))
    }

    @Test fun theAgentListPromisesFreeOnlyWithTheSwitchOn() {
        val choice = PresentAgentChoice(Lang.EN)
        val off = C.twoAgents(C.writing())
        val on = off.copy(status = off.status!!.copy(freeOnly = true))
        assertEquals(" · 1.19.5 · NVIDIA NIM, OpenCode Zen", choice.options(off, null)[1].desc)
        assertEquals(" · 1.19.5 · free providers: NVIDIA NIM, OpenCode Zen", choice.options(on, null)[1].desc)
        assertTrue(choice.optionsNote(off).contains("With OpenCode, free providers keep what they receive to improve their models."))
        assertTrue(choice.optionsNote(on).contains("OpenCode uses only free providers, which keep what they receive"))
    }

    @Test fun theGuardsEndingNamesNoSubscription() {
        assertEquals("Stopped: it was not on the login and model allowed.", en.turnError("billing_guard"))
        assertEquals("Parado: no iba con la sesión y el modelo permitidos.", es.turnError("billing_guard"))
        assertFalse(en.problemText("unsafe_agent_settings").contains("pay"))
    }
}
