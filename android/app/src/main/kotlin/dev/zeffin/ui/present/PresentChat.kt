// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.link.AppLink
import dev.zeffin.link.Attachment
import dev.zeffin.link.AttachmentKind
import dev.zeffin.link.ChatError
import dev.zeffin.link.ChatEvent
import dev.zeffin.link.ChatOut
import dev.zeffin.link.ChatState
import dev.zeffin.link.Conversation
import dev.zeffin.link.ChatStatus
import dev.zeffin.link.Effort
import dev.zeffin.link.Login
import dev.zeffin.link.inputText
import dev.zeffin.link.Mode
import dev.zeffin.link.text
import dev.zeffin.attach.AttachError
import dev.zeffin.attach.Upload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The chat's words in [l]: the server sends codes and data, the app says them (the same meanings as
 * server/bridge/chat/client.py, zf chat). Claude's text and Flipper data are shown as they come. [name] is the
 * agent's name (hello), in the texts about what belongs to it; without it they say "the agent".
 */
class PresentChat(private val l: Lang, private val name: String? = null) {
    /** The agent in the middle of a sentence. */
    private val agent get() = name ?: t("the agent", "el agente")

    /** The agent at the start of a sentence. */
    private val agentFirst get() = name ?: t("The agent", "El agente")

    /** What belongs to the agent: "Claude Code's" or "the agent's"; "de Claude Code" or "del agente". */
    private val agents get() = name?.let { t("$it's", "de $it") } ?: t("the agent's", "del agente")

    private fun t(en: String, es: String) = l.t(en, es)

    /** Remote Control's words for this server's agent (its name and codes come in hello). */
    private fun remoteWords(state: ChatState) = PresentRemote(l, state.remote)

    /** A mode the app knows in [l]; any other by the agent's own name for it ([status]'s options), or its value. */
    fun modeName(mode: Mode, status: ChatStatus? = null) = when (mode) {
        Mode.MANUAL -> "Manual"
        Mode.ACCEPT_EDITS -> t("Accept edits", "Aceptar ediciones")
        Mode.PLAN -> "Plan"
        Mode.AUTO -> t("Auto", "Automático")
        else -> status?.modeOption(mode)?.label ?: mode.wire
    }

    /** The hint of a mode the app knows; none for another. */
    fun modeDesc(mode: Mode): String? = when (mode) {
        Mode.MANUAL -> t("Asks you before anything that is not a read or a Flipper tool.",
            "Te pregunta antes de todo lo que no sea leer o una herramienta del Flipper.")
        Mode.ACCEPT_EDITS -> t("Edits files and runs common file commands without asking; asks for the rest.",
            "Edita ficheros y ejecuta comandos de ficheros comunes sin preguntar; pregunta por lo demás.")
        Mode.PLAN -> t("Reads and proposes a plan; changes nothing until you switch mode.",
            "Lee y propone un plan; no cambia nada hasta que cambies de modo.")
        Mode.AUTO -> t("Runs everything, with $agents background safety checks; shows what it blocks.",
            "Lo ejecuta todo, con las comprobaciones de seguridad $agents; muestra lo que bloquea.")
        else -> null
    }

    /** The mode sheet: the modes the agent offers, in its order. */
    fun modeOptions(status: ChatStatus?, chosen: Mode): List<OptionUi<Mode>> =
        status?.modeOptions.orEmpty().map { OptionUi(it.mode, modeName(it.mode, status), modeDesc(it.mode), it.mode == chosen) }

    fun effortName(effort: Effort) = when (effort) {
        Effort.LOW -> t("Low", "Bajo")
        Effort.MEDIUM -> t("Medium", "Medio")
        Effort.HIGH -> t("High", "Alto")
        Effort.XHIGH -> t("Extra high", "Muy alto")
        Effort.MAX -> t("Max", "Máximo")
    }

    fun effortDesc(effort: Effort) = when (effort) {
        Effort.LOW -> t("Quick answers, light thinking.", "Respuestas rápidas, pensando poco.")
        Effort.MEDIUM -> t("Balanced: where $agent starts these models.", "Equilibrado: donde $agent arranca estos modelos.")
        Effort.HIGH -> t("Thinks longer before acting.", "Piensa más antes de actuar.")
        Effort.XHIGH -> t("Deep thinking for hard problems.", "Pensamiento profundo para problemas difíciles.")
        Effort.MAX -> t("The most thinking; the slowest.", "Lo que más piensa; lo más lento.")
    }

    /** A model by the server's label; one it does not list by its value, as it is; none at all as "—". */
    fun modelName(value: String?, status: ChatStatus?) =
        status?.model(value)?.label?.takeIf { it.isNotBlank() } ?: value?.takeIf { it.isNotEmpty() } ?: "—"

    /**
     * The model list: every model the agent lists, in its order, by its label, [chosen] checked. One that is not
     * available is shown with its reason and cannot be picked. No words of the app's own: the label names it.
     */
    fun modelOptions(status: ChatStatus?, chosen: String?): List<OptionUi<String>> {
        val mixed = mixedImages(status)
        return status?.models.orEmpty().map {
            val images = when {
                !it.available || !mixed -> null
                it.images -> (Tone.SUCCESS to t("Takes images", "Admite imágenes")) to t(": sees the Flipper's screen", ": ve la pantalla del Flipper")
                else -> (Tone.WARNING to t("Text only", "Solo texto")) to t(": it cannot see the Flipper's screen", ": no puede ver la pantalla del Flipper")
            }
            // A paid model says so first, next to whether it takes images: "Paid · Takes images".
            val lead = if (it.available && it.paid) Tone.WARNING to (listOfNotNull(t("Paid", "De pago"), images?.first?.second)
                .joinToString(" · ")) else images?.first
            OptionUi(it.value, modelName(it.value, status), if (it.available) images?.second else unavailableText(it.reason, status),
                it.value == chosen, enabled = it.available, lead = lead)
        }
    }

    /** The agent lists models that take images and models that do not: then each row says which (the agent drives the
     *  Flipper by its screen). */
    private fun mixedImages(status: ChatStatus?) = status?.models.orEmpty().filter { it.available }.let { m ->
        m.any { it.images } && m.any { !it.images }
    }

    /**
     * Under the model list: the agent's models in its order. With free only on, free ones for an agent on providers
     * ([providers]) and those the plan includes for the others; off, a paid one says so.
     */
    fun modelNote(status: ChatStatus?, providers: Boolean): String =
        (if (status?.freeOnly != true) t("The models $agent lists, in its order; a paid one says so. One it cannot use is shown, but it cannot be chosen.",
            "Los modelos que lista $agent, en su orden; uno de pago lo dice. Uno que no puede usar aparece, pero no se puede elegir.")
        else if (providers) t("The free models $agent lists, in its order. One it cannot use is shown, but it cannot be chosen.",
            "Los modelos gratuitos que lista $agent, en su orden. Uno que no puede usar aparece, pero no se puede elegir.")
        else t("The models $agent lists, in its order. One your plan does not include is shown, but it cannot be chosen.",
            "Los modelos que lista $agent, en su orden. Uno que tu plan no incluye aparece, pero no se puede elegir.")) +
            (if (mixedImages(status)) t(" A text-only model still uses the Flipper's tools, but it works blind: it cannot read the screen.",
                " Un modelo de solo texto sigue usando las herramientas del Flipper, pero a ciegas: no puede leer la pantalla.") else "")

    private fun unavailableText(reason: String?, status: ChatStatus?) = when (reason) {
        "usage_credits" -> t("Not available: it would bill usage credits, outside your plan.",
            "No disponible: cobraría créditos de uso, fuera de tu plan.")
        "unchecked" -> t("Not available: the server cannot confirm your plan includes it.",
            "No disponible: el servidor no puede confirmar que tu plan lo incluya.")
        "not_signed_in" -> if (status?.freeOnly == true) t("Not available: free, but the agent is not signed in to its provider.",
            "No disponible: es gratis, pero el agente no tiene la sesión iniciada en su proveedor.")
            else t("Not available: the agent is not signed in to its provider.",
                "No disponible: el agente no tiene la sesión iniciada en su proveedor.")
        else -> t("Not available on the server.", "No disponible en el servidor.")
    }

    /**
     * Why [model] cannot take a message, or null when it can: not available, no longer listed, or none. Only judged
     * when the server lists its models.
     */
    fun modelProblem(model: String, status: ChatStatus?): String? {
        if (status == null || status.models.isEmpty()) return null
        val info = status.model(model)
        val then = t(" Choose another model to write here.", " Elige otro modelo para escribir aquí.")
        return when {
            model.isEmpty() -> t("This conversation has no model. Choose one to write here.",
                "Esta conversación no tiene modelo. Elige uno para escribir aquí.")
            info == null -> t("$model is no longer offered.", "$model ya no se ofrece.") + then
            info.available -> null
            else -> modelName(model, status) + when (info.reason) {
                "usage_credits" -> t(" would bill usage credits, outside your plan.", " cobraría créditos de uso, fuera de tu plan.")
                "unchecked" -> t(": the server cannot confirm your plan includes it.", ": el servidor no puede confirmar que tu plan lo incluya.")
                "not_signed_in" -> if (status.freeOnly) t(" is free, but the agent is not signed in to its provider.",
                    " es gratis, pero el agente no tiene la sesión iniciada en su proveedor.")
                    else t(": the agent is not signed in to its provider.", ": el agente no tiene la sesión iniciada en su proveedor.")
                else -> t(" is not available on the server.", " no está disponible en el servidor.")
            } + then
        }
    }

    fun problemText(code: String?) = when (code) {
        "not_configured" -> t("The agent is not set up: run zf agent setup on the server.",
            "El agente no está preparado: ejecuta zf agent setup en el servidor.")
        "claude_missing", "agent_missing" -> t("$agentFirst was not found on the server: run zf agent setup again.",
            "No se encuentra $agent en el servidor: vuelve a ejecutar zf agent setup.")
        "checking" -> t("The server is checking $agent; one moment.", "El servidor está comprobando $agent; un momento.")
        "not_logged_in" -> t("$agentFirst is not signed in on the server: sign it in there, then run zf restart.",
            "$agentFirst no tiene la sesión iniciada en el servidor: iníciala allí y luego ejecuta zf restart.")
        "not_subscription" -> t("$agentFirst does not use a subscription login, so the chat is refused.",
            "$agentFirst no usa el inicio de sesión de la suscripción, así que el chat se rechaza.")
        "unsafe_agent_settings" -> t("$agents settings on the server are not safe to run: run zf agent setup there to write them again.",
            "Los ajustes $agents en el servidor no son seguros: ejecuta zf agent setup allí para escribirlos de nuevo.")
        "stopping" -> t("The server is stopping.", "El servidor se está parando.")
        "agent_version" -> t("The server does not support this version of $agent: run zf agent setup on the server.",
            "El servidor no admite esta versión de $agent: ejecuta zf agent setup en el servidor.")
        else -> t("The chat is not ready ($code).", "El chat no está listo ($code).")
    }

    fun errorText(e: ChatError, remote: PresentRemote? = null): String = (remote ?: PresentRemote(l, null)).let { r ->
        r.errorText(e.code) ?: (if (e.code == "not_ready") r.problemText((e.data?.get("problem") as? JsonPrimitive)?.content) else null)
    } ?: when (e.code) {
        "busy" -> t("The agent is busy in another conversation.", "El agente está ocupado en otra conversación.")
        "expired" -> t("$agentFirst no longer has this conversation (deleted after its retention). Start a new one.",
            "$agentFirst ya no tiene esta conversación (se borró tras su plazo). Empieza una nueva.")
        "not_ready" -> problemText((e.data?.get("problem") as? JsonPrimitive)?.content)
        "too_long" -> t("The message is too long.", "El mensaje es demasiado largo.")
        "model_unavailable" -> t("That model is not available now: choose another one.", "Ese modelo no está disponible ahora: elige otro.")
        "bad_agent" -> t("The server does not know that agent.", "El servidor no conoce ese agente.")
        "bad_model", "bad_effort", "bad_mode" -> t("That choice is not offered by the server.", "El servidor no ofrece esa opción.")
        "bad_attachment" -> t("An attachment is no longer on the server: attach it again.", "Un adjunto ya no está en el servidor: adjúntalo otra vez.")
        "too_large" -> t("The images of this message are too large together.", "Las imágenes de este mensaje son demasiado grandes juntas.")
        "permission_gone" -> t("That question was already answered or expired.", "Esa pregunta ya se respondió o caducó.")
        "not_found" -> t("That conversation is not on the server.", "Esa conversación no está en el servidor.")
        else -> t("The server refused it (${e.code}).", "El servidor lo rechazó (${e.code}).")
    }

    fun noticeText(code: String, data: JsonObject?): String = when (code) {
        "rate_limit" -> {
            val resets = (data?.get("resetsAt") as? JsonPrimitive)?.doubleOrNull
            t("Subscription usage limit", "Límite de uso de la suscripción") +
                (resets?.let { t(", resets at ${Fmt.hmSeconds(it)}", ", se renueva a las ${Fmt.hmSeconds(it)}") } ?: "")
        }
        "auto_unavailable" -> t("Automatic mode is not available: this turn ran in Manual.",
            "El modo Automático no está disponible: este turno fue en Manual.")
        "flipper_mcp_failed" -> t("The Flipper tools did not connect for this turn.", "Las herramientas del Flipper no conectaron en este turno.")
        "api_retry" -> t("The agent's service did not answer; retrying.", "El servicio del agente no respondió; reintentando.")
        "compacted" -> t("The conversation was compacted to fit.", "La conversación se resumió para que quepa.")
        else -> code
    }

    /**
     * A failed turn's line. One the server refused before it started (the agent's settings or version, found unsafe
     * then) names the problem as the chat's own not-ready texts do, then what the server found, as its log says it.
     * One past the model's images (image_limit, 1.0.0) says what to do: send again once the server summarized the
     * conversation, or start a new one when it could not.
     */
    fun turnEndError(e: ChatEvent.TurnEnd): String = when (e.error) {
        "image_limit" -> imageLimitError(e)
        "agent_failed" -> agentFailedError(e)
        else -> turnError(e.error)
    }

    private fun imageLimitError(e: ChatEvent.TurnEnd): String {
        val compacted = (e.data?.get("compacted") as? JsonPrimitive)?.booleanOrNull == true
        val next = if (compacted) t(" It was summarized to make room: send your message again.", " Se resumió para hacer sitio: vuelve a enviar tu mensaje.")
        else t(" Start a new one.", " Empieza una nueva.")
        return turnError(e.error) + next
    }

    private fun agentFailedError(e: ChatEvent.TurnEnd): String {
        val problem = (e.data?.get("problem") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (problem == null) return turnError(e.error)
        val reason = (e.data["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { inputText(it, limit = 300) }
        return problemText(problem) + (reason?.takeIf { it.isNotBlank() }?.let { t(" The server found: $it.", " El servidor encontró: $it.") } ?: "")
    }

    fun turnError(code: String?) = when (code) {
        "billing_guard" -> t("Stopped: it was not on the login and model allowed.", "Parado: no iba con la sesión y el modelo permitidos.")
        "expired" -> t("$agentFirst no longer has this conversation.", "$agentFirst ya no tiene esta conversación.")
        "api_error" -> t("The agent's service failed.", "Falló el servicio del agente.")
        "max_turns" -> t("The agent reached its step limit.", "El agente llegó a su límite de pasos.")
        "server_restarted" -> t("The server restarted during the turn.", "El servidor se reinició durante el turno.")
        "stopping" -> t("The server stopped during the turn.", "El servidor se paró durante el turno.")
        "image_limit" -> t("Too many screens for this model in one conversation.", "Demasiadas pantallas para este modelo en una conversación.")
        else -> t("$agentFirst failed.", "$agentFirst falló.")  // claude_failed, agent_failed or a code the app does not know
    }

    private fun answerText(answer: String) = when (answer) {
        "once" -> Tone.SUCCESS to t("Allowed once", "Permitido una vez")
        "always" -> Tone.SUCCESS to t("Always allowed here", "Permitido siempre aquí")
        "deny" -> Tone.DANGER to t("Denied", "Denegado")
        "timeout" -> Tone.WARNING to t("No answer in time: denied", "Sin respuesta a tiempo: denegado")
        else -> Tone.NEUTRAL to t("Closed", "Cerrada")
    }

    /**
     * A file a tool card names: its name, not the server path; an attachment (stored as <id>.<ext>) by the name it
     * was sent with, from [attachments] (id to name). Permission cards keep the whole path, which a decision needs.
     */
    fun fileName(path: String, attachments: Map<String, String>): String {
        val parts = path.trimEnd('/').split('/')
        val i = parts.lastIndexOf("attachments")
        if (i >= 0 && i == parts.size - 3) attachments[parts.last().substringBefore('.')]?.let { return it + t(" (attachment)", " (adjunto)") }
        return parts.last().ifEmpty { path }
    }

    /** The input fields shown on several lines; every other one is shown on one. */
    private val multilineFields = setOf("command")

    /** "flipper_press", "Pressed DOWN, DOWN, OK": what a tool did, from its input (untrusted: cleaned by inputText). */
    fun toolText(name: String, input: JsonElement?, attachments: Map<String, String>? = null): String? {
        val o = input as? JsonObject
        if (attachments != null) for (key in listOf("file_path", "path")) {
            (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return inputText(fileName(it, attachments), limit = 300) }
        }
        if (name.endsWith("flipper_press")) {
            val keys = (o?.get("keys") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let { k -> inputText(k, limit = 40) } }
            if (!keys.isNullOrEmpty()) return inputText(t("Pressed ", "Pulsado ") + keys.joinToString(", "), limit = 300)
        }
        for (key in listOf("command", "file_path", "path", "url", "pattern", "description")) {
            (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return inputText(it, key in multilineFields, 300) }
        }
        return AppLink.summarize(input)
    }

    /**
     * A permission card's rows: the tool, then what the request acts on (its command, file, path, address or pattern),
     * then every other argument, each whole under its own label (the owner approves what they can see whole: Write's
     * content, Edit's texts, WebFetch's prompt, OpenCode's diff, an unknown tool's arguments). The agent's description
     * is labelled as the agent's. The card draws a long or multi-line value below its label, in a box that scrolls.
     */
    private fun toolRows(name: String, input: JsonElement?, home: String? = null): List<Pair<String, String>> {
        val rows = mutableListOf(t("Tool", "Herramienta") to inputText(name.removePrefix("mcp__flipper__"), limit = 120))
        val o = input as? JsonObject
        if (o == null) {
            argText(input)?.takeIf { it.isNotEmpty() }?.let { rows += t("Details", "Detalles") to it }
            return rows
        }
        val main = mainKeys.filter { it in o }
        for (key in main + o.keys.filter { it !in main }.sortedBy { it == "description" }) {
            val value = o[key]
            val text = if (key in main && value is JsonPrimitive && value.isString) inputText(value.content, key in multilineFields)
                else argText(value) ?: ""
            rows += argLabel(key) to if (key in pathKeys) HomePath.path(text, home) else text
        }
        return rows
    }

    /** The fields that name a path: shown with the server's home folder as "~" (HomePath). */
    private val pathKeys = setOf("file_path", "filepath", "filePath", "notebook_path", "path")

    /** The fields that say what a request acts on, first on its card: Claude Code's, then OpenCode's (its rawInput). */
    private val mainKeys = listOf("command", "file_path", "filepath", "filePath", "notebook_path", "path", "url", "pattern")

    /** Each argument's label: readable for the common ones, else the key itself, cleaned (it is untrusted). */
    private fun argLabel(key: String) = argLabels[key] ?: inputText(key, limit = 120)

    private val argLabels by lazy {
        val file = t("File", "Fichero")
        val old = t("Old text", "Texto anterior")
        val new = t("New text", "Texto nuevo")
        val all = t("Replace all", "Reemplazar todo")
        mapOf("command" to t("Command", "Comando"), "file_path" to file, "filepath" to file, "filePath" to file,
            "notebook_path" to t("Notebook", "Cuaderno"), "path" to t("Path", "Ruta"), "url" to "URL", "pattern" to t("Pattern", "Patrón"),
            "content" to t("Content", "Contenido"), "old_string" to old, "oldString" to old, "new_string" to new, "newString" to new,
            "replace_all" to all, "replaceAll" to all, "edits" to t("Edits", "Ediciones"), "diff" to t("Changes", "Cambios"),
            "prompt" to "Prompt", "description" to t("The agent's description", "Descripción del agente"))
    }

    /** One argument, whole: text with its line breaks; a list or an object as summarize writes it, uncapped. */
    private fun argText(e: JsonElement?): String? = when (e) {
        null -> null
        JsonNull -> "null"
        is JsonPrimitive -> inputText(e.content, multiline = true)
        is JsonArray -> e.joinToString(", ") { argText(it) ?: "" }
        is JsonObject -> e.entries.joinToString(", ") { (k, v) -> "${inputText(k, limit = 120)}=${argText(v) ?: ""}" }
    }

    /** What "Always allow" saves, every rule as the answer sends it (ChatStore.answer); none when it is not offered. */
    /** A request too long to show whole (the server cut it): what the card says above its only answer, Deny. */
    fun tooLong(p: ChatEvent.Permission): String? = if (p.whole) null else t(
        "Too long to show whole, so it can only be denied. Ask the agent to do it in smaller steps.",
        "Demasiado largo para mostrarlo entero, así que solo se puede denegar. Pide al agente que lo haga en pasos más pequeños.")

    private fun ruleRows(p: ChatEvent.Permission, home: String? = null) =
        if (!p.always || !p.whole) emptyList() else p.rules.map { t("Always allows", "Siempre permite") to HomePath.rule(it, home) }

    fun attachment(conversation: String, a: Attachment) = AttachmentUi(conversation, a.id, a.name, a.kind,
        (if (a.kind == AttachmentKind.IMAGE) t("Image", "Imagen") else a.mediaType.substringAfter('/').uppercase().take(12).let {
            when (it) { "OCTET-STREAM" -> t("File", "Fichero"); "PLAIN" -> "TXT"; else -> it } }) + " · " + Fmt.bytes(l, a.bytes), a.width, a.height)

    /** Settings' Agent row: whether the chat can be used. */
    fun agentLine(state: ChatState): StatusLine? {
        val status = state.status
        return when {
            state.supported == false -> StatusLine(Tone.WARNING, t("Server too old", "Servidor antiguo"), t("run zf update", "ejecuta zf update"))
            status == null -> null
            status.problem == null -> StatusLine(Tone.SUCCESS, t("Ready", "Listo"), listOfNotNull("$agentFirst ${state.agentVersion ?: ""}".trim(),
                modelName(status.settings.defaultModel, status), modeName(status.settings.defaultMode, status)).joinToString(" · "))
            status.problem == "checking" -> StatusLine(Tone.NEUTRAL, t("Checking…", "Comprobando…"), null)
            else -> StatusLine(Tone.WARNING, t("Not available", "No disponible"), problemText(status.problem).substringBefore(":"))
        }
    }

    /** Under the conversation's settings and their lists. */
    val sheetSub get() = t("$agentFirst · this conversation · from your next message", "$agentFirst · esta conversación · desde tu próximo mensaje")

    /** The mode sheet's note. */
    val modeNote get() = t("The same modes as $agent.", "Los mismos modos que $agent.")

    fun timeoutName(seconds: Int) = when {
        seconds % 60 == 0 -> "${seconds / 60} min"
        seconds > 60 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "$seconds s"
    }

    /** The login method as the server words it, marked when it is a subscription. */
    private fun loginName(login: Login?): String {
        val method = login?.method ?: return "—"
        return if (login.subscription) t("Subscription ($method)", "Suscripción ($method)") else method
    }

    /** Settings, Agent. */
    fun agentUi(state: ChatState): AgentUi {
        val status = state.status
        val s = status?.settings ?: dev.zeffin.link.ChatSettings()
        val levels = status?.model(s.defaultModel)?.efforts ?: Effort.entries
        val line = agentLine(state) ?: StatusLine(Tone.NEUTRAL, t("Not connected", "Sin conexión"), null)
        return AgentUi(
            name = agentFirst,
            version = state.agentVersion ?: "—",
            login = loginName(status?.login),
            state = StatusLine(line.tone, line.state, if (status?.problem != null && status.problem != "checking") problemText(status.problem) else null),
            model = modelName(s.defaultModel, status),
            effort = if (levels.isEmpty()) null else effortName(s.defaultEffort),
            mode = modeName(s.defaultMode, status),
            timeout = timeoutName(s.permissionTimeoutS),
            ready = status?.ready == true,
        )
    }

    private fun uploadError(e: AttachError) = when (e) {
        AttachError.TooBig -> t("Over 10 MB", "Más de 10 MB")
        AttachError.Unreadable -> t("Could not be read", "No se pudo leer")
        is AttachError.Server -> when (e.code) {
            "too_large" -> t("Too large", "Demasiado grande")
            "bad_type" -> t("Type not accepted", "Tipo no admitido")
            "bad_image" -> t("Not a valid image", "No es una imagen válida")
            "unauthorized" -> t("App token rejected", "App token rechazado")
            "not_found", "bad_conversation" -> t("Conversation not found", "Conversación no encontrada")
            else -> t("Refused (${e.code})", "Rechazado (${e.code})")
        }
        is AttachError.Net -> t("No connection · tap to retry", "Sin conexión · toca para reintentar")
    }

    /** The tray above the composer. */
    /** "17,230 characters" / "17.230 caracteres". */
    fun characters(n: Int) = t("${Fmt.count(l, n)} characters", "${Fmt.count(l, n)} caracteres")

    /** Under the tray while it holds a long paste: why the text became a file. */
    fun trayNote(items: List<Upload>): String? = if (items.none { it.chars != null }) null else {
        val max = Fmt.count(l, ChatOut.MAX_TEXT)
        t("A paste over $max characters goes as a text file the agent reads.", "Un pegado de más de $max caracteres va como fichero de texto que el agente lee.")
    }

    /**
     * The message is over the server's limit and cannot go: typed past it, or pasted with Remote Control on, where no
     * file can go (an older server). Null while it fits.
     */
    fun tooLong(chars: Int, remote: PresentRemote?): ChatBanner? {
        if (chars <= ChatOut.MAX_TEXT) return null
        val count = Fmt.count(l, chars)
        val text = t("Messages can have at most ${Fmt.count(l, ChatOut.MAX_TEXT)} characters; this one has $count.",
            "Los mensajes pueden tener como máximo ${Fmt.count(l, ChatOut.MAX_TEXT)} caracteres; este tiene $count.") +
            (remote?.let { r -> t(" With ${r.name} on, messages carry no attachments: shorten it, or turn ${r.name} off to send it as a text file.",
                " Con ${r.name} activo, los mensajes no llevan adjuntos: acórtalo o apaga ${r.name} para enviarlo como fichero de texto.") } ?: "")
        return ChatBanner(Tone.DANGER, t("The message is too long", "El mensaje es demasiado largo"), text, strong = count)
    }

    /** The composer as the tray and the draft leave it: files ready or uploading, and a text over the limit. */
    fun composed(chat: ChatUi, state: ChatState, tray: List<Upload>, draft: String): ChatUi {
        val tooLong = tooLong(draft.length, if (state.remoteNoFiles) PresentRemote(l, state.remote) else null)
        return chat.copy(banner = tooLong ?: chat.banner, composer = chat.composer.copy(hasAttachments = tray.any { it.done },
            uploading = tray.any { it.busy }, tooLong = tooLong != null))
    }

    fun trayUi(items: List<Upload>): List<TrayUi> = items.map { u ->
        val kind = if (u.kind == AttachmentKind.IMAGE) t("Image", "Imagen")
            else u.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() && it.length <= 5 } ?: t("File", "Fichero")
        val meta = when {
            u.chars != null -> characters(u.chars)
            u.busy -> t("Uploading", "Subiendo") + " · " + Fmt.bytes(l, u.bytes)
            else -> "$kind · ${Fmt.bytes(l, u.bytes)}"
        }
        TrayUi(u.localId, AttachmentUi("", "", u.name, u.kind, meta, u.preview?.width, u.preview?.height), u.preview != null,
            progress = if (u.busy) u.progress ?: 0f else null, error = u.error?.let(::uploadError))
    }

    /**
     * Home's card, always while the server has the chat: the conversation where the agent works (or the last one
     * opened), what the agent does in it, and a pending permission to answer from Home with the same rows and
     * buttons as in the conversation. A plan is approved only in the conversation, where it can be read.
     */
    fun lastConversation(state: ChatState, streaming: Map<String, String> = emptyMap()): LastConversationUi? {
        val turn = state.status?.turn
        val c = state.conversations.firstOrNull { it.id == turn?.conversation } ?: state.current
        return when {
            state.supported != true -> null
            c == null -> LastConversationUi(null, t("No conversation yet", "Aún no hay conversaciones"), null,
                t("Start one to drive the Flipper with the agent.", "Empieza una para manejar el Flipper con el agente."), isNew = true)
            else -> cardOf(state, c, streaming)
        }
    }

    private fun cardOf(state: ChatState, c: Conversation, streaming: Map<String, String>): LastConversationUi {
        val turn = state.status?.turn
        val pending = state.status?.pending?.firstOrNull()?.takeIf { turn?.conversation == c.id && turn.waiting }
        val plan = pending?.tool == "ExitPlanMode"
        val character = PresentCharacter(l).conversation(state, c.id, streaming)
        val st = when {
            turn?.conversation != c.id -> null
            turn.waiting -> Tone.WARNING to (if (plan) t("A plan waits for your approval", "Un plan espera tu aprobación")
                else t("Waiting for your permission", "Esperando tu permiso"))
            character.face == Face.WRITING -> Tone.ACTION to t("The agent is writing", "El agente está escribiendo")
            else -> Tone.ACTION to t("The agent is working", "El agente está trabajando")
        }
        val permission = pending?.takeIf { !plan }?.let { PermissionUi(it.id, toolRows(it.tool, it.input, state.home), it.always && it.whole, ruleRows(it, state.home), tooLong(it)) }
        val remoteOn = state.remote != null && state.status?.remote?.conversation == c.id
        return LastConversationUi(c.id, c.title.ifBlank { t("New conversation", "Conversación nueva") }, st,
            if (permission != null) null else c.last.ifBlank { null }, isNew = false, permission = permission,
            remote = if (remoteOn) remoteWords(state).iconLabel else null, character = character)
    }

    /** A conversation's screen: items in order (oldest first), the composer, a banner, and the conversation settings. */
    /**
     * [ui] with the server key's banner ([keyTrouble]: it changed, or the pairing has none) or the tunnel's when /app is
     * down because the private network's tunnel does not reach the server (AppLinkState.noTunnel): nothing was sent
     * either way. It goes before any other banner.
     */
    fun withTunnel(ui: ChatUi, state: ChatState, noTunnel: Boolean, keyTrouble: KeyTrouble? = null): ChatUi = Present(l).let {
        val pair = listOf(ChatAction.PAIR to l.t("Pair with QR code", "Emparejar con el QR"))
        when {
            keyTrouble == KeyTrouble.CHANGED -> ui.copy(banner = ChatBanner(Tone.DANGER, it.keyChangedTitle(), it.keyChangedText(), pair))
            keyTrouble == KeyTrouble.PAIR_AGAIN -> ui.copy(banner = ChatBanner(Tone.WARNING, it.pairAgainTitle(), it.pairAgainText(), pair))
            !noTunnel || state.connected -> ui
            else -> ui.copy(banner = ChatBanner(Tone.WARNING, it.noTunnelTitle(), it.noTunnelText()))
        }
    }

    /**
     * The conversation's agent, when the server knows several: it can change while nothing was written in it (the app
     * then starts the conversation again with the other agent), and is kept after that.
     */
    fun conversationAgent(state: ChatState): ConvAgentUi? {
        val agents = state.agentList
        val conversation = state.current
        val id = conversation?.agent ?: state.status?.settings?.defaultAgent ?: agents.firstOrNull()?.id
        val entry = agents.firstOrNull { it.id == id }?.takeIf { agents.size > 1 } ?: return null
        val fresh = state.open == null || conversation?.turns == 0
        val words = PresentAgentChoice(l)
        return ConvAgentUi(
            name = entry.info.name,
            desc = if (fresh) t("It stays once you write", "Se queda en cuanto escribas")
                else t("Kept for this conversation. Another agent: start a new one.", "Se queda en esta conversación. Otro agente: empieza una nueva."),
            canChange = fresh && !state.busyHere,
            options = words.options(state, entry.id),
            note = words.optionsNote(state),
            sub = t("New conversation · each conversation keeps its agent", "Conversación nueva · cada conversación guarda su agente"),
        )
    }

    fun chatUi(state: ChatState, streaming: Map<String, String>): ChatUi {
        val status = state.status
        val open = state.open
        val conversation = state.current
        val home = state.home
        val items = mutableListOf<ChatItem>()
        val remote = remoteWords(state)
        val remoteHere = state.remoteHere
        val remoteTurn = status?.turn?.takeIf { it.remote && it.conversation == open?.id }
        if (open != null) {
            val events = open.events
            val ended = events.filterIsInstance<ChatEvent.PermissionEnd>().associate { it.id to it.answer }
            val results = events.filterIsInstance<ChatEvent.ToolResult>().associateBy { it.id }
            val pending = status?.pending?.map { it.id }.orEmpty().toSet()
            val lastTurnStart = events.indexOfLast { it is ChatEvent.TurnStart }
            val sent = events.filterIsInstance<ChatEvent.User>().flatMap { it.attachments }.associate { it.id to it.name }
            var mode: Mode? = null
            for ((index, e) in events.withIndex()) {
                val key = "e${e.n}"
                when (e) {
                    is ChatEvent.User -> items += ChatItem.Mine(key, e.text, e.attachments.map { attachment(open.id, it) },
                        Fmt.hmSeconds(e.at), remote = e.via == "remote")
                    is ChatEvent.TurnStart -> mode = e.mode
                    is ChatEvent.Text -> if (e.text.isNotBlank()) items += ChatItem.Claude(key, e.text, false)
                    is ChatEvent.Tool -> {
                        val r = results[e.id]
                        val meta = when {
                            r == null -> t("running…", "en curso…")
                            else -> listOfNotNull(r.ms?.let { Fmt.seconds(l, it) }, r.screen?.let { t("frame ${it.seq}", "fotograma ${it.seq}") })
                                .joinToString(" · ").ifEmpty { null }
                        }
                        val text = if (r?.error == true) r.text?.lineSequence()?.firstOrNull()?.take(200) else toolText(e.name, e.input, sent)
                        items += ChatItem.Tool(key, e.name.removePrefix("mcp__flipper__"), text,
                            if (e.via == "remote") listOfNotNull(Fmt.hmSeconds(e.at), meta).joinToString(" · ") else meta,
                            r?.error == true, r?.screen, remote = e.via == "remote")
                    }
                    is ChatEvent.Permission -> {
                        val answer = ended[e.id]
                        val waiting = answer == null && e.id in pending
                        val state = if (waiting) Tone.WARNING to t("Waiting", "Esperando")
                            else answerText(answer ?: "closed")
                        if (e.tool == "ExitPlanMode") {
                            val plan = ((e.input as? JsonObject)?.get("plan") as? JsonPrimitive)?.content?.let { inputText(it, multiline = true) } ?: ""
                            items += ChatItem.Plan(key, e.id, plan, state)
                        } else if (waiting) {
                            items += ChatItem.Ask(key, e.id, t("The agent asks for permission", "El agente pide permiso"),
                                toolRows(e.tool, e.input, home), e.always && e.whole, state, ruleRows(e, home), tooLong(e))
                        } else {
                            items += ChatItem.Line(key, state.first, state.second + " · " +
                                (toolText(e.tool, e.input)?.let { "${e.tool}: $it" } ?: e.tool))
                        }
                    }
                    is ChatEvent.Denied -> if (e.by == "auto") {
                        val rows = toolRows(e.tool, e.input, state.home) + listOfNotNull(e.reason?.let { t("Reason", "Motivo") to it })
                        items += ChatItem.Blocked(key, rows, canRetry = index > lastTurnStart && !state.busyHere && !state.busyElsewhere)
                    } else {
                        items += ChatItem.Line(key, Tone.WARNING, t("Not allowed in this mode", "No permitido en este modo") +
                            " · " + (toolText(e.tool, e.input)?.let { "${e.tool}: $it" } ?: e.tool))
                    }
                    is ChatEvent.Notice -> if (e.code.startsWith("remote_")) {
                        remote.notice(e.code, e.data)?.let { items += ChatItem.Line(key, Tone.NEUTRAL, it) }
                    } else items += ChatItem.Line(key, Tone.WARNING, noticeText(e.code, e.data))
                    is ChatEvent.TurnEnd -> when (e.outcome) {
                        "interrupted" -> items += ChatItem.Line(key, Tone.NEUTRAL, t("Stopped", "Parado"),
                            Face.STOPPED.takeIf { index == events.lastIndex })
                        "error" -> items += ChatItem.Line(key, Tone.DANGER, turnEndError(e), Face.ERROR.takeIf { index == events.lastIndex })
                        else -> if (mode == Mode.PLAN && index == events.lastIndex) items += ChatItem.Line(key, Tone.NEUTRAL,
                            t("Plan changes nothing: switch the mode and tell the agent to go ahead.",
                                "Plan no cambia nada: cambia el modo y dile al agente que adelante."))
                    }
                    is ChatEvent.ToolResult, is ChatEvent.PermissionEnd, is ChatEvent.TurnInfo, is ChatEvent.Unknown -> {}
                }
            }
            for ((id, text) in streaming) items += ChatItem.Claude("s$id", text, true)
            if (remoteTurn != null) {
                // Remote Control streams nothing: say the reply comes whole, or that a permission waits on the other device.
                if (remoteTurn.remoteWaiting) {
                    val tool = (events.lastOrNull { it is ChatEvent.Notice && it.code == "remote_permission" } as? ChatEvent.Notice)
                        ?.data?.get("tool")?.let { (it as? JsonPrimitive)?.content?.let { tool -> inputText(tool, limit = 120) } }
                    items += ChatItem.Waiting("remote-waiting", remote.waitingTitle, remote.waitingLine,
                        listOfNotNull(tool?.let { t("Tool", "Herramienta") to it }), Tone.WARNING to t("Waiting", "Esperando"))
                } else items += ChatItem.Line("remote-working", Tone.NEUTRAL, remote.working)
            } else if (state.busyHere && streaming.isEmpty() && items.lastOrNull().let { it !is ChatItem.Ask && it !is ChatItem.Plan &&
                    !(it is ChatItem.Tool && it.meta == t("running…", "en curso…")) }) {
                items += ChatItem.Claude("working", "", true)
            }
        }
        val model = conversation?.model ?: status?.settings?.defaultModel ?: ""
        val modelProblem = if (conversation != null) modelProblem(model, status) else null
        val mode = conversation?.mode ?: status?.settings?.defaultMode ?: Mode.MANUAL
        val effort = conversation?.effort
        val ready = state.supported == true && state.connected && status?.ready == true
        val agentUi = conversationAgent(state)
        val fresh = open == null || conversation?.turns == 0
        val nothingYet = open == null || (conversation?.turns == 0 && open.events.isEmpty())
        val shownAgent = state.agentList.firstOrNull { it.id == (conversation?.agent ?: status?.settings?.defaultAgent) }
        // a new conversation whose agent is not ready, while another one is: choose another
        val otherAgent = fresh && agentUi?.canChange == true && state.agentList.any { it.ready }
        val askAgent = status?.problem != null && state.connected && otherAgent && shownAgent != null
        val banner = when {
            state.supported == false -> ChatBanner(Tone.WARNING, t("The chat needs a server update", "El chat necesita actualizar el servidor"),
                t("Run zf update on the server.", "Ejecuta zf update en el servidor."))
            state.error != null -> ChatBanner(Tone.DANGER, errorText(state.error, remote), null, listOfNotNull(
                if (state.error.code == "busy") ChatAction.OPEN_BUSY to t("Open that conversation", "Abrir esa conversación") else null,
                ChatAction.DISMISS to t("OK", "Vale")))
            askAgent && shownAgent != null -> ChatBanner(Tone.WARNING,
                t("${shownAgent.info.name} is not ready", "${shownAgent.info.name} no está listo"),
                AgentWords(l).state(shownAgent).second + ": " + (AgentWords(l).short(shownAgent) ?: "") +
                    t(". Choose another agent to write here.", ". Elige otro agente para escribir aquí."),
                listOf(ChatAction.CHOOSE_AGENT to t("Choose an agent", "Elegir un agente")))
            status?.problem != null && state.connected -> ChatBanner(Tone.WARNING, t("The chat is not available", "El chat no está disponible"),
                problemText(status.problem))
            modelProblem != null && state.connected -> ChatBanner(Tone.WARNING, t("This conversation's model is not available",
                "El modelo de esta conversación no está disponible"), modelProblem,
                listOf(ChatAction.CHOOSE_MODEL to t("Choose a model", "Elegir un modelo")))
            state.busyElsewhere && state.connected -> ChatBanner(Tone.ACTION, t("The agent is working in another conversation",
                "El agente está trabajando en otra conversación"), null,
                listOf(ChatAction.OPEN_BUSY to t("Open it", "Abrirla"), ChatAction.STOP_BUSY to t("Stop it", "Pararlo")))
            else -> null
        }
        val levels = status?.model(model)?.efforts ?: Effort.entries
        val settings = ConversationSettingsUi(
            agent = agentUi,
            modelLabel = modelName(model, status),
            models = modelOptions(status, model),
            modelNote = modelNote(status, shownAgent?.let(AgentWords(l)::providers) ?: false),
            efforts = levels,
            effortWords = Effort.entries.associateWith { effortName(it) to effortDesc(it) },
            effort = effort ?: if (levels.isEmpty()) null else (status?.settings?.defaultEffort?.takeIf { it in levels } ?: levels.first()),
            modeLabel = modeName(mode, status),
            modes = modeOptions(status, mode),
            modeNote = modeNote,
            remote = if (state.remote != null && conversation != null) RemoteUi(remote.name,
                remote.rowDesc(remoteHere && status?.remote?.starting == true), remoteHere, remote.iconLabel,
                confirmOff = remoteHere && state.busyHere) else null,
            locked = if (remoteHere) remote.locked else null,
            sub = if (agentUi != null && fresh) t("New conversation · from its first message", "Conversación nueva · desde su primer mensaje")
                else sheetSub,
            note = t("${name?.let { "$it's" } ?: "The agent's"} own settings. Flipper tools never ask in any mode, and Pause agent still rules.",
                "Los ajustes propios $agents. Las herramientas del Flipper nunca preguntan en ningún modo, y Pausar sigue mandando."),
        )
        val effortShown = settings.effort?.let(::effortName)
        return ChatUi(
            available = state.supported != false,
            title = conversation?.title?.ifBlank { null } ?: t("New conversation", "Conversación nueva"),
            settingsLine = listOfNotNull(agentUi?.name, settings.modelLabel, effortShown, settings.modeLabel).joinToString(" · "),
            items = items,
            // A model that cannot take the message: sending waits until another is chosen (the banner says so)
            composer = ComposerUi(enabled = ready && !(remoteHere && status?.remote?.starting == true) && (modelProblem == null || state.busyHere),
                busy = state.busyHere,
                canAttach = ready && state.attachments && !state.remoteNoFiles, attachHidden = state.remoteNoFiles),
            banner = banner,
            settings = settings,
            empty = if (nothingYet && state.supported != false)
                t("Write to start a conversation with the agent.", "Escribe para empezar una conversación con el agente.") else null,
            agentCard = agentUi?.takeIf { it.canChange }?.copy(desc = t("The last one chosen. It stays once you write.",
                "El último elegido. Se queda en cuanto escribas.")),
            remoteOn = remoteHere,
        )
    }
}
