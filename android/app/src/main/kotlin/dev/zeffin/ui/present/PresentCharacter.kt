// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.core.PairingRecord
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatState
import dev.zeffin.link.Conn
import dev.zeffin.link.inputText
import dev.zeffin.relay.RelayState
import dev.zeffin.relay.Stage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Zeffin's character: the launcher icon's face, one expression per state (docs/design/system/components/Character.md). */
enum class Face { ACTIVE, WORKING, WRITING, PERMISSION, PAUSED, STOPPED, FINISHED, ERROR, CONNECTING, OFFLINE }

/** What the character acts out while a tool runs. */
enum class Scene { SCREEN, PRESS, FILES, APP, OTHER }

/**
 * The character in one place: its [face], the [scene] it acts out while it works, and, at the chat's tail, the [line]
 * that says the same in words. [data] is what came from the agent or the Flipper (a path, a file, a tool): shown in
 * mono after the line, in quotes when [quote]. [event] is the chat event the face answers (a turn's end), so a face
 * that plays once plays again for the next one.
 */
data class CharacterUi(
    val face: Face,
    val scene: Scene? = null,
    val line: String? = null,
    val data: String? = null,
    val quote: Boolean = true,
    val tone: Tone = Tone.NEUTRAL,
    val event: Int? = null,
)

/** Chooses the character's face from the app's state, and words its line. Pure: no Compose, no clock. */
class PresentCharacter(private val l: Lang) {
    private fun t(en: String, es: String) = l.t(en, es)

    /**
     * The shared device's state, for Home's app bar and its screen without an image: the pairing, the connection to
     * the server, the relay and the gate. A turn never shows here: it belongs to a conversation.
     */
    fun device(relay: RelayState, link: AppLinkState, pairing: PairingRecord?): Face {
        val status = link.status
        val trouble = keyTrouble(link, pairing, relay)
        val gate = status?.gate ?: "active"
        return when {
            pairing?.appToken == null -> Face.OFFLINE
            trouble == KeyTrouble.CHANGED || link.conn == Conn.REJECTED -> Face.ERROR
            trouble == KeyTrouble.PAIR_AGAIN -> Face.OFFLINE
            link.conn == Conn.CONNECTING -> Face.CONNECTING
            link.conn != Conn.CONNECTED || status == null -> Face.OFFLINE
            gate == "stopped" -> Face.STOPPED
            !status.connected -> if (relay.stage in CONNECTING) Face.CONNECTING else Face.OFFLINE
            gate == "paused" -> Face.PAUSED
            else -> Face.ACTIVE
        }
    }

    /**
     * One conversation's state, for Home's card: a permission waiting, a tool in use, the agent's text arriving, or,
     * with no turn running, how its last turn ended. The events are known only for the open conversation; for
     * another one a running turn reads as writing, as Home's card says today.
     */
    fun conversation(state: ChatState, id: String?, streaming: Map<String, String>): CharacterUi {
        val turn = state.status?.turn?.takeIf { id != null && it.conversation == id }
        val events = state.open?.takeIf { it.id == id }?.events
        val end = events?.lastOrNull { it is ChatEvent.TurnEnd } as? ChatEvent.TurnEnd
        return when {
            turn != null -> running(state, turn.waiting, events, streaming)
            end == null -> CharacterUi(Face.ACTIVE)
            end.outcome == "interrupted" -> CharacterUi(Face.STOPPED, event = end.n)
            end.outcome == "error" -> CharacterUi(Face.ERROR, event = end.n)
            else -> CharacterUi(Face.FINISHED, event = end.n)
        }
    }

    /**
     * A conversation's app bar: the device's trouble first (no server, a changed key, connecting), then what the
     * agent does in it, then the gate, else how the conversation stands.
     */
    fun bar(device: Face, conversation: Face): Face = when {
        device == Face.OFFLINE || device == Face.ERROR || device == Face.CONNECTING -> device
        conversation in setOf(Face.PERMISSION, Face.WORKING, Face.WRITING, Face.ERROR) -> conversation
        device == Face.PAUSED || device == Face.STOPPED -> device
        else -> conversation
    }

    /**
     * The chat's tail in the open conversation: the character and its line while a turn runs, and, for the 3 s after
     * a turn ends well ([justEnded], kept by the screen), the bounce with smiling eyes. Null when nothing is shown there.
     */
    fun tail(state: ChatState, streaming: Map<String, String>, justEnded: Boolean): CharacterUi? {
        val open = state.open
        val turn = state.status?.turn?.takeIf { open != null && it.conversation == open.id && !it.remote }
        val end = open?.events?.lastOrNull() as? ChatEvent.TurnEnd
        return when {
            open == null -> null
            turn != null -> running(state, turn.waiting, open.events, streaming)
            justEnded && end?.outcome == "ok" -> CharacterUi(Face.FINISHED, line = t("The agent finished", "El agente terminó"), event = end.n)
            else -> null
        }
    }

    /** A conversation's screen with its character: the chat's [tail] and the bar's face, from the [device]'s face. */
    fun chat(ui: ChatUi, state: ChatState, streaming: Map<String, String>, device: Face, justEnded: Boolean): ChatUi =
        ui.copy(tail = tail(state, streaming, justEnded), face = bar(device, conversation(state, state.open?.id, streaming).face))

    private fun running(state: ChatState, waiting: Boolean, events: List<ChatEvent>?, streaming: Map<String, String>): CharacterUi {
        val plan = waiting && state.status?.pending?.firstOrNull()?.tool == "ExitPlanMode"
        val turnEvents = events?.let { it.drop(it.indexOfLast { e -> e is ChatEvent.TurnStart }.coerceAtLeast(0)) }
        val done = turnEvents?.filterIsInstance<ChatEvent.ToolResult>()?.map { it.id }?.toSet().orEmpty()
        val tool = turnEvents?.lastOrNull { it is ChatEvent.Tool && it.id !in done } as? ChatEvent.Tool
        return when {
            waiting -> CharacterUi(Face.PERMISSION, line = if (plan) t("A plan waits for your approval", "Un plan espera tu aprobación")
                else t("Waiting for your permission", "Esperando tu permiso"))
            tool != null -> toolUi(tool.name, tool.input)
            events == null || streaming.isNotEmpty() -> CharacterUi(Face.WRITING, line = t("The agent is writing", "El agente está escribiendo"))
            else -> CharacterUi(Face.WORKING, Scene.OTHER, t("The agent is working", "El agente está trabajando"))
        }
    }

    /** A tool's line: its words in both languages and the input field shown as data ([file]: only its last name). */
    private class Line(val en: String, val es: String, val field: String? = null, val file: Boolean = false)

    private val lines = mapOf(
        "flipper_screen" to Line("Looking at the screen", "Mirando la pantalla"),
        "flipper_wait_screen" to Line("Waiting for the screen to change", "Esperando a que cambie la pantalla"),
        "flipper_watch" to Line("Pressing and watching the screen", "Pulsando y mirando la pantalla"),
        "flipper_list" to Line("Opening the folder", "Abriendo la carpeta", "path"),
        "flipper_mkdir" to Line("Making the folder", "Creando la carpeta", "path"),
        "flipper_read" to Line("Reading", "Leyendo", "path", file = true),
        "flipper_stat" to Line("Checking", "Comprobando", "path", file = true),
        "flipper_write" to Line("Saving", "Guardando", "path", file = true),
        "flipper_delete" to Line("Deleting", "Borrando", "path", file = true),
        "flipper_rename" to Line("Renaming", "Renombrando", "old_path", file = true),
        "flipper_app_start" to Line("Opening", "Abriendo", "name"),
        "flipper_app_load_file" to Line("Loading in the app", "Cargando en la app", "path", file = true),
        "flipper_app_button" to Line("Pressing a button in the app", "Pulsando un botón en la app"),
        "flipper_app_exit" to Line("Closing the app", "Cerrando la app"),
    )

    /** The scene and the line for a tool in use, from its name and its input (untrusted: cleaned, and shown as data). */
    fun toolUi(name: String, input: JsonElement?): CharacterUi {
        val tool = name.removePrefix(MCP_PREFIX)
        val o = input as? JsonObject
        val line = lines[tool]
        return when {
            tool == "flipper_press" -> press(o)
            line != null -> CharacterUi(Face.WORKING, scene(tool), t(line.en, line.es), line.field?.let { data(o, it, line.file) })
            else -> CharacterUi(Face.WORKING, Scene.OTHER, t("Using", "Usando"), inputText(tool, limit = 60), quote = false)
        }
    }

    private fun press(o: JsonObject?): CharacterUi {
        val keys = (o?.get("keys") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let { k -> inputText(k, limit = 40) } }
            ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val line = if (keys == null) t("Pressing keys", "Pulsando teclas") else inputText(t("Pressing ", "Pulsando ") + keys, limit = 120)
        return CharacterUi(Face.WORKING, Scene.PRESS, line)
    }

    /** An input field as it is shown: cleaned, cut, and for a file only its last name; null when empty. */
    private fun data(o: JsonObject?, key: String, file: Boolean): String? {
        val value = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { inputText(it, limit = 120) }
        return (if (file) value?.trimEnd('/')?.substringAfterLast('/') else value)?.ifBlank { null }
    }

    companion object {
        private const val MCP_PREFIX = "mcp__flipper__"

        /** The relay's stages on the way to a session: the face looks for a signal. */
        val CONNECTING = setOf(Stage.CHECKING_SERVER, Stage.WAITING_SERVER, Stage.CONNECTING_FLIPPER, Stage.WAITING_FLIPPER,
            Stage.OPENING_SESSION, Stage.RETRYING)

        /** How long the chat's tail shows the end of a turn that went well. */
        const val FINISHED_MS = 3_000L

        /** The scene a tool acts out: the screen, the button, a folder, an app's loading bar, or thinking (any other). */
        fun scene(tool: String): Scene = when (tool.removePrefix(MCP_PREFIX)) {
            "flipper_screen", "flipper_wait_screen" -> Scene.SCREEN
            "flipper_press", "flipper_watch" -> Scene.PRESS
            "flipper_list", "flipper_stat", "flipper_read", "flipper_write", "flipper_mkdir", "flipper_delete", "flipper_rename" -> Scene.FILES
            "flipper_app_start", "flipper_app_load_file", "flipper_app_button", "flipper_app_exit" -> Scene.APP
            else -> Scene.OTHER  // flipper_app only reads the catalog; the agent's own tools too
        }
    }
}
