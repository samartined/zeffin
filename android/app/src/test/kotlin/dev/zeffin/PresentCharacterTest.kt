// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatState
import dev.zeffin.link.Conn
import dev.zeffin.link.Mode
import dev.zeffin.link.RunningTurn
import dev.zeffin.relay.Stage
import dev.zeffin.ui.present.CharacterUi
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.PresentCharacter
import dev.zeffin.ui.present.Scene
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import dev.zeffin.ChatFixtures as C
import dev.zeffin.Fixtures as F

/** The character's face in each state, its tool scenes and its words (docs/design/system/components/Character.md). */
class PresentCharacterTest {
    private val en = PresentCharacter(Lang.EN)
    private val es = PresentCharacter(Lang.ES)
    private fun json(s: String) = Json.parseToJsonElement(s)

    // ------------------------------------------------------------------------------------------- the device

    @Test fun deviceActivePausedStopped() {
        assertEquals(Face.ACTIVE, en.device(F.relayRunning, F.link, F.pairing))
        assertEquals(Face.PAUSED, en.device(F.relayRunning, F.link.copy(status = F.status.copy(gate = "paused")), F.pairing))
        assertEquals(Face.STOPPED, en.device(F.relayStopped, F.link.copy(status = F.status.copy(gate = "stopped", connected = false)), F.pairing))
    }

    @Test fun deviceOfflineAndErrors() {
        assertEquals("not paired", Face.OFFLINE, en.device(F.relayRunning, F.link, null))
        assertEquals("no tunnel", Face.OFFLINE, en.device(F.relayRunning, F.linkNoTunnel, F.pairing))
        assertEquals("pair again", Face.OFFLINE, en.device(F.relayRunning, F.linkPairAgain, F.pairing))
        assertEquals("key changed", Face.ERROR, en.device(F.relayRunning, F.linkKeyChanged, F.pairing))
        assertEquals("token rejected", Face.ERROR, en.device(F.relayRunning, F.link.copy(conn = Conn.REJECTED, status = null), F.pairing))
        val noRelay = F.link.copy(status = F.status.copy(connected = false))
        assertEquals("no relay", Face.OFFLINE, en.device(F.relayStopped, noRelay, F.pairing))
    }

    @Test fun deviceConnecting() {
        assertEquals(Face.CONNECTING, en.device(F.relayRunning, F.link.copy(conn = Conn.CONNECTING, status = null), F.pairing))
        val noRelay = F.link.copy(status = F.status.copy(connected = false))
        for (stage in PresentCharacter.CONNECTING) {
            assertEquals(stage.name, Face.CONNECTING, en.device(F.relayRunning.copy(stage = stage), noRelay, F.pairing))
        }
        assertEquals(Face.OFFLINE, en.device(F.relayRunning.copy(stage = Stage.ERROR), noRelay, F.pairing))
    }

    // ------------------------------------------------------------------------------------------- a conversation

    private fun withTool(state: ChatState, name: String, input: String = "{}"): ChatState {
        val open = state.open!!
        val n = open.lastN + 1
        val tool = ChatEvent.Tool(n, 1_793_000_000.0, "t$n", name, json(input))
        return state.copy(open = open.copy(events = open.events + tool))
    }

    private fun ended(outcome: String): ChatState {
        val s = C.writing()
        val open = s.open!!
        return s.copy(status = s.status!!.copy(turn = null),
            open = open.copy(events = open.events + ChatEvent.TurnEnd(open.lastN + 1, 1_793_000_000.0, 1, outcome, null, null, 2_000)))
    }

    @Test fun conversationRunning() {
        assertEquals(Face.WRITING, en.conversation(C.writing(), C.ID, C.streaming).face)
        assertEquals("a tool answered, nothing new yet", CharacterUi(Face.WORKING, Scene.OTHER, "The agent is working"),
            en.conversation(C.writing(), C.ID, emptyMap()))
        assertEquals(Face.PERMISSION, en.conversation(C.asking(), C.ID, emptyMap()).face)
        val looking = en.conversation(withTool(C.writing(), "mcp__flipper__flipper_screen"), C.ID, emptyMap())
        assertEquals(Face.WORKING to Scene.SCREEN, looking.face to looking.scene)
    }

    @Test fun anotherConversationRunningReadsAsWriting() {
        val s = C.writing().copy(open = null)
        assertEquals(Face.WRITING, en.conversation(s, C.ID, emptyMap()).face)
        assertEquals("no turn there", Face.ACTIVE, en.conversation(s, "another", emptyMap()).face)
    }

    @Test fun conversationIdleSaysHowTheLastTurnEnded() {
        assertEquals(Face.FINISHED, en.conversation(ended("ok"), C.ID, emptyMap()).face)
        assertEquals(Face.ERROR, en.conversation(ended("error"), C.ID, emptyMap()).face)
        assertEquals(Face.STOPPED, en.conversation(ended("interrupted"), C.ID, emptyMap()).face)
        assertEquals("the end's event, so a new end plays again", ended("ok").open!!.lastN, en.conversation(ended("ok"), C.ID, emptyMap()).event)
        assertEquals("no turn ended yet", Face.ACTIVE, en.conversation(C.modelUnavailable(), C.ID, emptyMap()).face)
    }

    @Test fun barPutsTheDevicesTroubleFirst() {
        assertEquals(Face.OFFLINE, en.bar(Face.OFFLINE, Face.WORKING))
        assertEquals(Face.ERROR, en.bar(Face.ERROR, Face.ACTIVE))
        assertEquals(Face.CONNECTING, en.bar(Face.CONNECTING, Face.FINISHED))
        assertEquals(Face.PERMISSION, en.bar(Face.PAUSED, Face.PERMISSION))
        assertEquals(Face.WRITING, en.bar(Face.ACTIVE, Face.WRITING))
        assertEquals(Face.PAUSED, en.bar(Face.PAUSED, Face.FINISHED))
        assertEquals(Face.STOPPED, en.bar(Face.STOPPED, Face.ACTIVE))
        assertEquals(Face.FINISHED, en.bar(Face.ACTIVE, Face.FINISHED))
    }

    // ------------------------------------------------------------------------------------------- the chat's tail

    @Test fun tailWhileTheTurnRuns() {
        val pressing = en.tail(withTool(C.writing(), "mcp__flipper__flipper_press", """{"keys":["DOWN","DOWN","OK"]}"""), emptyMap(), false)
        assertEquals(CharacterUi(Face.WORKING, Scene.PRESS, "Pressing DOWN, DOWN, OK"), pressing)
        assertEquals(CharacterUi(Face.WRITING, line = "The agent is writing"), en.tail(C.writing(), C.streaming, false))
        assertEquals(CharacterUi(Face.PERMISSION, line = "Waiting for your permission"), en.tail(C.asking(), emptyMap(), false))
        assertEquals("Esperando tu permiso", es.tail(C.asking(), emptyMap(), false)?.line)
    }

    @Test fun tailAfterTheTurn() {
        assertEquals(CharacterUi(Face.FINISHED, line = "The agent finished", event = ended("ok").open!!.lastN),
            en.tail(ended("ok"), emptyMap(), justEnded = true))
        assertEquals("El agente terminó", es.tail(ended("ok"), emptyMap(), justEnded = true)?.line)
        assertNull("gone after its 3 s", en.tail(ended("ok"), emptyMap(), justEnded = false))
        assertNull("an error keeps its own line in the chat", en.tail(ended("error"), emptyMap(), justEnded = true))
        assertNull("no conversation open", en.tail(C.writing().copy(open = null), emptyMap(), false))
    }

    @Test fun tailIsNotShownForRemoteControlTurns() {
        val s = C.writing().let { it.copy(status = it.status!!.copy(turn = RunningTurn(C.ID, 1, Mode.MANUAL, "opus", null, 0.0,
            waiting = false, remote = true))) }
        assertNull(en.tail(s, emptyMap(), false))
    }

    // ------------------------------------------------------------------------------------------- tools

    /** Every tool the server offers (server/bridge/tools.py) and the scene it acts out. */
    private val expected = mapOf(
        "flipper_screen" to Scene.SCREEN, "flipper_wait_screen" to Scene.SCREEN,
        "flipper_press" to Scene.PRESS, "flipper_watch" to Scene.PRESS,
        "flipper_list" to Scene.FILES, "flipper_stat" to Scene.FILES, "flipper_read" to Scene.FILES, "flipper_write" to Scene.FILES,
        "flipper_mkdir" to Scene.FILES, "flipper_delete" to Scene.FILES, "flipper_rename" to Scene.FILES,
        "flipper_app_start" to Scene.APP, "flipper_app_load_file" to Scene.APP, "flipper_app_button" to Scene.APP,
        "flipper_app_exit" to Scene.APP,
        "flipper_app" to Scene.OTHER, "flipper_status" to Scene.OTHER, "flipper_device_info" to Scene.OTHER,
        "flipper_firmware" to Scene.OTHER, "flipper_power_info" to Scene.OTHER, "flipper_rpc" to Scene.OTHER,
        "flipper_events" to Scene.OTHER, "flipper_note" to Scene.OTHER,
    )

    @Test fun everyServerToolHasItsScene() {
        val tools = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "server/bridge/tools.py") }.first { it.exists() }
        val names = Regex("""async def (flipper_\w+)\(""").findAll(tools.readText()).map { it.groupValues[1] }.toSet()
        assertEquals("a new tool needs its scene here and in Character.md", names, expected.keys)
        for ((name, scene) in expected) {
            assertEquals(name, scene, PresentCharacter.scene(name))
            assertEquals("with the MCP prefix", scene, PresentCharacter.scene("mcp__flipper__$name"))
        }
        assertEquals("the agent's own tools", Scene.OTHER, PresentCharacter.scene("Bash"))
    }

    @Test fun toolLinesInBothLanguages() {
        fun ui(p: PresentCharacter, name: String, input: String = "{}") = p.toolUi("mcp__flipper__$name", json(input))
        assertEquals(CharacterUi(Face.WORKING, Scene.SCREEN, "Looking at the screen"), ui(en, "flipper_screen"))
        assertEquals("Mirando la pantalla", ui(es, "flipper_screen").line)
        assertEquals("Waiting for the screen to change", ui(en, "flipper_wait_screen").line)
        assertEquals("Pulsando DOWN, OK", ui(es, "flipper_press", """{"keys":["DOWN","OK"]}""").line)
        assertEquals("Pressing keys", ui(en, "flipper_press").line)
        assertEquals("Pressing and watching the screen", ui(en, "flipper_watch", """{"until":"change"}""").line)
        assertEquals("Opening the folder" to "/ext/infrared", ui(en, "flipper_list", """{"path":"/ext/infrared"}""").let { it.line to it.data })
        assertEquals("Leyendo" to "TV.ir", ui(es, "flipper_read", """{"path":"/ext/infrared/TV.ir"}""").let { it.line to it.data })
        assertEquals("Renaming" to "Old.ir", ui(en, "flipper_rename", """{"old_path":"/ext/infrared/Old.ir","new_path":"/ext/a.ir"}""")
            .let { it.line to it.data })
        assertEquals("Opening" to "Infrared", ui(en, "flipper_app_start", """{"name":"Infrared"}""").let { it.line to it.data })
        assertEquals("Closing the app", ui(en, "flipper_app_exit").line)
        val other = ui(en, "flipper_status")
        assertEquals(CharacterUi(Face.WORKING, Scene.OTHER, "Using", "flipper_status", quote = false), other)
        assertEquals("Usando" to "Bash", es.toolUi("Bash", null).let { it.line to it.data })
    }

    @Test fun toolDataIsCleanedAsUntrustedInput() {
        val ui = en.toolUi("mcp__flipper__flipper_list", json("""{"path":"/ext/‮evil\u0007"}"""))
        assertEquals("/ext/evil", ui.data)
        assertNull("an empty path shows no data", en.toolUi("mcp__flipper__flipper_read", json("""{"path":""}""")).data)
        assertEquals(120, en.toolUi("mcp__flipper__flipper_list", json("""{"path":"${"a".repeat(500)}"}""")).data?.length)
    }
}
