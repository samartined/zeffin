// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.ChatError
import dev.zeffin.link.ChatStore
import dev.zeffin.link.Deletions
import dev.zeffin.ui.present.ChatAction
import dev.zeffin.ui.present.AgentWords
import dev.zeffin.ui.present.PresentAgentChoice
import dev.zeffin.ui.present.PresentAgents
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentConversations
import dev.zeffin.ui.present.Tone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.zeffin.ChatFixtures as C

private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private fun conversation(id: String, turns: Int, agent: String) =
    """{"id":"$id","created":1,"updated":2,"title":"","last":"","pinned":false,"favorite":false,"flipper":false,"mode":"default",
       "model":"opus","effort":"medium","rules":[],"state":"new","turns":$turns,"events":0,"attachments":0,"remote":false,"agent":"$agent"}"""

/** The agent chosen per conversation, and several conversations deleted at once. */
class AgentChoiceStoreTest {
    private val sent = mutableListOf<JsonObject>()
    private val store = ChatStore({ sent += it; true })
    private fun types() = sent.map { it["type"]!!.jsonPrimitive.content }

    private fun start(old: String = "c-old", turns: Int = 0) {
        store.onHello(setOf("chat", "delete"))
        store.onMessage(j("""{"type":"chat.conversations","items":[${conversation(old, turns, "claude-code")}]}"""))
        store.open(old)
        sent.clear()
    }

    @Test fun anotherAgentStartsTheEmptyConversationAgainAndDeletesTheOldOne() {
        start()
        assertTrue(store.chooseAgent("opencode"))
        assertEquals(listOf("chat.settings", "chat.new"), types())
        assertEquals("opencode", sent[0]["default_agent"]!!.jsonPrimitive.content)  // the last one chosen
        assertEquals("opencode", sent[1]["agent"]!!.jsonPrimitive.content)
        sent.clear()
        store.onMessage(j("""{"type":"chat.conversation","item":${conversation("c-new", 0, "opencode")}}"""))
        assertEquals("c-new", store.state.value.open?.id)  // the new one is open before the old one goes
        assertEquals(listOf("chat.delete"), types())
        assertEquals("c-old", sent[0]["conversation"]!!.jsonPrimitive.content)
    }

    @Test fun aConversationWrittenInKeepsItsAgent() {
        start(turns = 3)
        assertFalse(store.chooseAgent("opencode"))
        assertTrue(sent.isEmpty())
    }

    @Test fun theSameAgentOnlyBecomesTheDefault() {
        start()
        store.chooseAgent("claude-code")
        assertEquals(listOf("chat.settings"), types())
    }

    @Test fun deletingSeveralFollowsEachAnswer() {
        store.onHello(setOf("chat", "delete"))
        store.onMessage(j("""{"type":"chat.conversations","items":[${conversation("a", 2, "claude-code")},${conversation("b", 2, "claude-code")},
            ${conversation("c", 2, "opencode")}]}"""))
        sent.clear()
        assertTrue(store.deleteAll(listOf("a", "b", "c", "gone")))
        assertEquals(listOf("chat.delete", "chat.delete", "chat.delete"), types())  // only the ones the list has
        store.onMessage(j("""{"type":"chat.deleted","conversation":"a"}"""))
        store.onMessage(j("""{"type":"error","error":"not_found","request":"chat.delete"}"""))
        store.onMessage(j("""{"type":"chat.deleted","conversation":"c"}"""))
        val d = store.state.value.deletions!!
        assertTrue(d.done)
        assertEquals(2, d.deleted)
        assertEquals(setOf("b"), d.failed.keys)
        assertNull(store.state.value.error)  // the list says it; no chat banner later
        store.dismissDeletions()
        assertNull(store.state.value.deletions)
    }
}

class PresentAgentsTest {
    private val en = PresentAgents(Lang.EN)
    private val choice = PresentAgentChoice(Lang.EN)
    private val chat = PresentChat(Lang.EN, "Claude Code")

    @Test fun eachStateInItsWordsAndColor() {
        val w = AgentWords(Lang.EN)
        assertEquals(Tone.SUCCESS to "Ready", w.state(C.openCode))
        assertEquals(Tone.WARNING to "Not signed in", w.state(C.openCodeSignedOut))
        assertEquals(Tone.DANGER to "Not set up", w.state(C.openCodeNotSetUp))
        assertEquals(Tone.WARNING to "Sin sesión", AgentWords(Lang.ES).state(C.openCodeSignedOut))
        assertEquals("Subscription (claude.ai)", w.login(C.claudeCode))
        assertEquals("NVIDIA NIM, OpenCode Zen", w.login(C.openCode))  // as the server names them
        assertEquals("None signed in", w.login(C.openCodeSignedOut))
        assertEquals("no provider is signed in on the server", w.short(C.openCodeSignedOut))
    }

    @Test fun settingsNamesEachAgentWhileTheyFitThenCounts() {
        val two = C.twoAgents(C.writing(), C.openCodeSignedOut)
        assertEquals(listOf(Tone.SUCCESS to "Claude Code ready", Tone.WARNING to "OpenCode not signed in"), en.summary(two))
        val three = two.copy(status = two.status!!.copy(agents = two.status!!.agents + C.openCode.copy(id = "other", info = C.openCode.info.copy(name = "Other"))))
        assertEquals(listOf(Tone.SUCCESS to "3 agents: 2 ready", Tone.WARNING to "1 not signed in"), en.summary(three))
    }

    @Test fun theListAndEachPage() {
        val two = C.twoAgents(C.writing(), C.openCodeSignedOut)
        assertEquals(listOf(" · 2.1.308 · Subscription (claude.ai)", " · 1.19.5 · no provider signed in"), en.rows(two).map { it.rest })
        val claude = en.info(C.claudeCode)
        assertEquals(listOf("Version", "Login", "Remote Control"), claude.rows.map { it.first })
        assertNull(claude.privacy)  // only for free providers
        val open = en.info(C.openCode, freeOnly = true)
        assertEquals(listOf("Version", "Free providers"), open.rows.map { it.first })  // no Remote Control row without it
        assertTrue(open.privacy!!.startsWith("Free providers keep what they receive"))
        assertTrue(open.note!!.contains("No Remote Control"))
        val out = en.info(C.openCodeSignedOut)
        assertEquals("No provider is signed in", out.problem!!.first)
        assertTrue(out.problem!!.second.contains("zf agent setup --agent opencode"))
    }

    @Test fun onlyReadyAgentsCanBeChosen() {
        val options = choice.options(C.twoAgents(C.writing(), C.openCodeNotSetUp), "claude-code")
        assertEquals(listOf(true, false), options.map { it.enabled })
        assertEquals(listOf(true, false), options.map { it.selected })
        assertTrue(options[1].desc!!.contains("Run zf agent setup --agent opencode there"))
    }

    @Test fun settingsAgentShowsTheDefaultAgentsOwnDefaults() {
        val opencode = C.twoAgents(C.writing(), default = "opencode")
        val prefs = choice.prefs(opencode, chat)
        assertEquals("OpenCode", prefs.agent)
        assertEquals("Nemotron 3.5 Super VL", prefs.model)
        assertNull(prefs.effort)  // its models list no levels
        assertEquals("New conversations: OpenCode · Nemotron 3.5 Super VL · Manual", choice.prefsLine(opencode, chat))
        assertNull(choice.prefs(C.writing(), chat).agent)  // a server that lists no agents: no Agent row
    }
}

class ConversationAgentTest {
    private val en = PresentChat(Lang.EN, "Claude Code")
    private val es = PresentChat(Lang.ES, "Claude Code")

    @Test fun oneAgentShowsNoAgentRow() {
        assertNull(en.conversationAgent(C.writing()))
        assertEquals("Opus · Medium · Manual", en.chatUi(C.writing(), emptyMap()).settingsLine)
    }

    @Test fun theAgentCanChangeUntilTheFirstMessage() {
        val fresh = en.chatUi(C.newConversation(), emptyMap())
        assertTrue(fresh.settings!!.agent!!.canChange)
        assertEquals("Claude Code · Opus 5.5 · Medium · Manual", fresh.settingsLine)
        assertEquals("The last one chosen. It stays once you write.", fresh.agentCard!!.desc)
        val written = en.chatUi(C.withOpenCode(), emptyMap())
        assertFalse(written.settings!!.agent!!.canChange)
        assertNull(written.agentCard)
        assertEquals("OpenCode · Nemotron 3.5 Super VL · Manual", written.settingsLine)  // no dial: no levels
    }

    @Test fun modelsSayWhetherTheyTakeImagesOnlyWhenTheListMixesThem() {
        val open = en.modelOptions(C.withOpenCode().status, null)
        assertEquals(listOf("Takes images", "Takes images", "Text only", "Text only", "Takes images"), open.map { it.lead?.second })
        assertEquals("Admite imágenes", es.modelOptions(C.withOpenCode().status, null).first().lead!!.second)
        assertTrue(en.modelOptions(C.writing(models = C.exampleModels).status, null).all { it.lead == null })
    }

    @Test fun anAgentThatIsNotReadyAsksForAnother() {
        val ui = en.chatUi(C.newConversation(C.openCodeSignedOut, default = "opencode"), emptyMap())
        assertEquals("OpenCode is not ready", ui.banner!!.title)
        assertEquals(listOf(ChatAction.CHOOSE_AGENT), ui.banner!!.actions.map { it.first })
        assertFalse(ui.composer.enabled)
    }

    @Test fun unsafeSettingsNameNoKeyOfAnyKind() {
        val text = en.problemText("unsafe_agent_settings")
        assertFalse(text.contains("API"))
        assertTrue(text.contains("are not safe to run"))  // neither "pay" nor "leak": true with the switch on or off
        assertFalse(es.problemText("unsafe_agent_settings").contains("API"))
    }
}

class SelectionWordsTest {
    private val en = PresentConversations(Lang.EN, "Claude Code")
    private val es = PresentConversations(Lang.ES, "Claude Code")
    private val state = C.listing().copy(deletion = true)
    private val saved = C.list[0].id
    private val nfc = C.list[1].id
    private val notes = C.list[3].id

    @Test fun pinsAllUnlessAllArePinned() {
        val shown = C.list.map { it.id }
        val mixed = en.selectionUi(state, setOf(notes, nfc), shown, false, "")
        assertTrue(mixed.pin)          // one is not pinned: Pin pins both
        assertFalse(mixed.favorite)    // both are favorites: Unfavorite
        assertFalse(mixed.single)
        assertEquals("2 selected", mixed.title)
        assertEquals("All conversations · 6 in the list", mixed.sub)
        assertFalse(en.selectionUi(state, setOf(notes), shown, false, "").pin)
        assertEquals("All conversations · all 6 selected", en.selectionUi(state, shown.toSet(), shown, false, "").sub)
        assertEquals("Matching “nfc” · 1 in the list", en.selectionUi(state, setOf(nfc), listOf(nfc), false, "nfc").sub)
        assertEquals("1 seleccionada", es.selectionUi(state, setOf(nfc), shown, false, "").title)
    }

    @Test fun deletingSeveralSaysHowManyAndThatARunningTurnStops() {
        val ui = en.deleteMany(state, listOf(notes, saved, nfc))
        assertEquals("Delete 3 conversations?", ui.title)
        assertEquals("31 messages · 2 attachments", ui.sub)
        assertTrue(ui.text.contains("“Saved infrared remotes” is running: its turn stops first."))
        assertEquals("¿Borrar 3 conversaciones?", es.deleteMany(state, listOf(notes, saved, nfc)).title)
    }

    @Test fun whatCouldNotBeDeletedSaysWhy() {
        val after = state.copy(deletions = Deletions(listOf(notes, saved), emptySet(), mapOf(saved to ChatError("not_connected", null, "chat.delete"))))
        val ui = en.deletionsUi(after, PresentChat(Lang.EN))!!
        assertEquals("1 deleted, 1 not deleted", ui.title)
        assertTrue(ui.text.startsWith("“Saved infrared remotes”: the app was not connected to the server, so it was kept."))
        assertNull(en.deletionsUi(state.copy(deletions = Deletions(listOf(notes), setOf(notes))), PresentChat(Lang.EN)))  // still waiting
    }
}
