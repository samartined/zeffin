// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.link.RemoteCapability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The words of the agent's remote access (Claude Code's Remote Control on a conversation).
 * The server sends names and codes ([RemoteCapability]: the feature's name, who keeps the conversation, where
 * permissions are answered); the sentences are the app's, in both languages, so no text here names an agent.
 */
class PresentRemote(private val l: Lang, private val cap: RemoteCapability?) {
    private fun t(en: String, es: String) = l.t(en, es)

    /** The feature's own name, never translated; a generic name if an older server gave none. */
    val name: String get() = cap?.name ?: t("Remote access", "Acceso remoto")

    /** The switch row's description: where else it can be written, and who keeps it while it is on. */
    fun rowDesc(starting: Boolean): String = when {
        starting -> t("Starting…", "Arrancando…")
        else -> listOfNotNull(t("Also from your other devices.", "También desde tus otros dispositivos."),
            cap?.keeper?.let { t("$it keeps this conversation while it is on.", "$it guarda esta conversación mientras está activo.") })
            .joinToString(" ")
    }

    /** The app bar's icon, for TalkBack. */
    val iconLabel: String get() = t("$name on", "$name activo")

    /** A message or a tool that came from another device. */
    val fromElsewhere: String get() = t("from another device", "desde otro dispositivo")

    /** Under the settings while it is on: the running process keeps its own. */
    val locked: String get() = t("While $name is on, the model, effort and mode stay as they are. Turn it off to change them.",
        "Con $name activo, el modelo, el esfuerzo y el modo se quedan como están. Apágalo para cambiarlos.")

    /** While a turn runs: no text is streamed, the reply comes whole. */
    val working: String get() = t("The agent is working. With $name on, its reply shows here when it ends.",
        "El agente está trabajando. Con $name activo, su respuesta sale aquí cuando termina.")

    val waitingTitle: String get() = t("The agent asks for permission", "El agente pide permiso")

    /** Where a permission is answered while it is on: "elsewhere" is the only code today. */
    val waitingLine: String get() = t("Waiting for your answer on another device", "Esperando tu respuesta en otro dispositivo")

    val offTitle: String get() = t("Turn off $name?", "¿Apagar $name?")
    val offText: String get() = t("The agent is working; turning off stops its turn. The conversation goes on here as before, " +
        "with everything said from other devices; there it shows as ended.",
        "El agente está trabajando; al apagarlo se para su turno. La conversación sigue aquí como antes, " +
            "con todo lo dicho desde otros dispositivos; allí se verá como terminada.")
    val offSub: String get() = t("$name · the agent is working", "$name · el agente está trabajando")
    val offStop: String get() = t("Stop it and turn off", "Pararlo y apagar")
    val offKeep: String get() = t("Keep $name on", "Mantener $name activo")

    /** A notice in the history, or null when nothing is shown for it. */
    fun notice(code: String, data: JsonObject?): String? {
        val reason = (data?.get("reason") as? JsonPrimitive)?.content
        return when (code) {
            "remote_off" -> when (reason) {
                "off", "deleted" -> null  // the owner's own action: the icon going away says it
                else -> t("$name ended: ${endReason(reason)}. Everything said from other devices is here, and the conversation goes on as before.",
                    "$name terminó: ${endReason(reason)}. Todo lo dicho desde otros dispositivos está aquí, y la conversación sigue como antes.")
            }
            "remote_on", "remote_permission" -> null  // shown live: the icon, and the waiting card
            else -> null
        }
    }

    private fun endReason(reason: String?) = when (reason) {
        "server_restarted" -> t("the server restarted", "el servidor se reinició")
        "server_stopped" -> t("the server stopped", "el servidor se paró")
        "idle" -> t("8 hours without activity", "8 horas sin actividad")
        "could_not_start" -> t("it could not start", "no pudo arrancar")
        else -> t("its process ended", "su proceso terminó")
    }

    fun errorText(code: String): String? = when (code) {
        "remote_command" -> t("With $name on, a message can't start with / ! or #.", "Con $name activo, un mensaje no puede empezar por / ! o #.")
        "remote_attachments" -> t("With $name on, messages carry no attachments.", "Con $name activo, los mensajes no llevan adjuntos.")
        "remote_busy" -> t("$name is on in another conversation: turn it off there first.",
            "$name está activo en otra conversación: apágalo allí primero.")
        "remote_settings" -> locked
        "remote_unsupported" -> t("This conversation's agent has no remote access.",
            "El agente de esta conversación no tiene acceso remoto.")
        else -> null
    }

    fun problemText(code: String?): String? = when (code) {
        "remote_starting" -> t("$name is still starting; try again in a moment.", "$name aún está arrancando; inténtalo en un momento.")
        "remote_ending" -> t("$name is turning off.", "$name se está apagando.")
        "agent_folder_untrusted" -> t("The agent's folder is not trusted yet: run zf agent setup on the server.",
            "La carpeta del agente aún no es de confianza: ejecuta zf agent setup en el servidor.")
        else -> null
    }
}
