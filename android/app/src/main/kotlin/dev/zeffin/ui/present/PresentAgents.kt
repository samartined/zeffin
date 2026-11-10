// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.link.AgentEntry
import dev.zeffin.link.AppProtocol.AgentId
import dev.zeffin.link.AppProtocol.Problem
import dev.zeffin.link.ChatState
import dev.zeffin.link.Effort

/** One agent in Settings, Agents: its name, its state word and the rest of its line ("2.1.308 · Subscription (claude.ai)"). */
data class AgentRowUi(val id: String, val name: String, val state: Pair<Tone, String>, val rest: String)

/** One agent's own page: its state, its rows, a line on what free providers keep, and either a note or why it is not ready. */
data class AgentInfoUi(
    val name: String,
    val state: Pair<Tone, String>,
    val rows: List<Triple<String, String, Tone?>>,
    /** Only for an agent on providers (OpenCode): what the free ones keep. */
    val privacy: String?,
    val note: String?,
    /** Why it cannot take a turn, and what to do on the server: a title and its text. */
    val problem: Pair<String, String>?,
)

/** Settings, Agent: where new conversations start (the agent last chosen, and its model, effort and mode), and the timeout. */
data class AgentPrefsUi(
    /** The agent of new conversations; null when the server lists no agents (an older one): no Agent row. */
    val agent: String?,
    val model: String,
    val effort: String?,
    val mode: String,
    val timeout: String,
    /** Under the New conversations card. */
    val note: String,
)

/**
 * Each agent's state, login and why it is not ready, in [l]: the words every agent screen shares. [freeOnly]: the
 * owner's "free only" switch is on (chat.status's free_only); only then do they promise "free".
 */
class AgentWords(private val l: Lang, private val freeOnly: Boolean = false) {
    private fun t(en: String, es: String) = l.t(en, es)

    /** An agent on providers: every agent but Claude Code, which runs with its own login. */
    fun providers(a: AgentEntry) = a.id != AgentId.CLAUDE_CODE

    /** An agent on providers while free only is on: its providers and models are free ones. */
    fun free(a: AgentEntry) = freeOnly && providers(a)

    /** "Ready", "Not signed in", "Not set up", "Checking…" or "Not available", in its color. */
    fun state(a: AgentEntry): Pair<Tone, String> = when {
        a.ready -> Tone.SUCCESS to t("Ready", "Listo")
        a.problem == Problem.CHECKING -> Tone.NEUTRAL to t("Checking…", "Comprobando…")
        a.problem == Problem.NOT_CONFIGURED -> Tone.DANGER to t("Not set up", "Sin preparar")
        a.problem == Problem.NOT_LOGGED_IN || a.problem == Problem.NOT_SUBSCRIPTION -> Tone.WARNING to t("Not signed in", "Sin sesión")
        else -> Tone.WARNING to t("Not available", "No disponible")
    }

    /** The same state inside a sentence: "Claude Code ready", "OpenCode not signed in". */
    fun stateAfterName(a: AgentEntry): String = when {
        a.ready -> t("${a.info.name} ready", "${a.info.name} listo")
        a.problem == Problem.CHECKING -> t("${a.info.name} being checked", "${a.info.name} en comprobación")
        a.problem == Problem.NOT_CONFIGURED -> t("${a.info.name} not set up", "${a.info.name} sin preparar")
        a.problem == Problem.NOT_LOGGED_IN || a.problem == Problem.NOT_SUBSCRIPTION ->
            t("${a.info.name} not signed in", "${a.info.name} sin sesión")
        else -> t("${a.info.name} not available", "${a.info.name} no disponible")
    }

    /** How it is signed in: a subscription by its method, providers by the server's words, or "None". */
    fun login(a: AgentEntry): String {
        val method = a.login?.method?.takeIf { it.isNotBlank() }
        return when {
            method == null -> if (providers(a)) t("None signed in", "Ninguno con sesión") else t("None", "Ninguno")
            a.login?.subscription == true -> t("Subscription ($method)", "Suscripción ($method)")
            else -> method
        }
    }

    /** Why it is not ready, and what to do on the server; null when it is ready. */
    fun reason(a: AgentEntry): String? {
        val setup = "zf agent setup --agent ${a.id}"
        return when (a.problem) {
            null -> null
            Problem.NOT_CONFIGURED -> t("It is not set up on this server. Run $setup there.",
                "No está preparado en este servidor. Ejecuta $setup allí.")
            Problem.CLAUDE_MISSING, Problem.AGENT_MISSING -> t("The server no longer finds it. Run $setup there again.",
                "El servidor ya no lo encuentra. Vuelve a ejecutar $setup allí.")
            Problem.CHECKING -> t("The server is checking it; one moment.", "El servidor lo está comprobando; un momento.")
            Problem.NOT_LOGGED_IN -> notSignedIn(a, setup)
            Problem.NOT_SUBSCRIPTION -> t("Its login is not your subscription, so the server refuses it.",
                "Su sesión no es tu suscripción, así que el servidor lo rechaza.")
            Problem.UNSAFE_AGENT_SETTINGS -> unsafe(setup)
            Problem.AGENT_VERSION -> t("The server does not support this version. Run $setup there.",
                "El servidor no admite esta versión. Ejecuta $setup allí.")
            Problem.AGENT_FOLDER_UNTRUSTED -> t("Its folder on the server is not trusted. Run $setup there.",
                "Su carpeta en el servidor no es de confianza. Ejecuta $setup allí.")
            Problem.STOPPING -> t("The server is stopping.", "El servidor se está parando.")
            else -> t("It cannot take a turn now (${a.problem}).", "Ahora no puede atender un turno (${a.problem}).")
        }
    }

    /** Not signed in: to free providers (free only on), to providers, or to its own login. */
    private fun notSignedIn(a: AgentEntry, setup: String): String {
        val name = a.info.name
        return when {
            free(a) -> t("No free provider is signed in. On the server, sign $name in, then run zf restart. $setup says how.",
                "Ningún proveedor gratuito tiene la sesión iniciada. En el servidor, inicia la sesión de $name y luego ejecuta zf restart. $setup dice cómo.")
            providers(a) -> t("No provider is signed in. On the server, sign $name in, then run zf restart. $setup says how.",
                "Ningún proveedor tiene la sesión iniciada. En el servidor, inicia la sesión de $name y luego ejecuta zf restart. $setup dice cómo.")
            else -> t("It has no login on the server. Sign it in there, then run zf restart.",
                "No tiene la sesión iniciada en el servidor. Iníciala allí y luego ejecuta zf restart.")
        }
    }

    /** Settings that could make it leak, or also pay while free only is on. */
    private fun unsafe(setup: String) = if (freeOnly) t("Its settings on the server could make it pay or leak. Run $setup there to write them again.",
        "Sus ajustes en el servidor podrían hacerle pagar o filtrar datos. Ejecuta $setup allí para escribirlos de nuevo.")
        else t("Its settings on the server could make it leak. Run $setup there to write them again.",
            "Sus ajustes en el servidor podrían filtrar datos. Ejecuta $setup allí para escribirlos de nuevo.")

    /** Why it is not ready, in a few words for a line or a warning ("no provider is signed in on the server"); null when ready. */
    fun short(a: AgentEntry): String? = when (a.problem) {
        null -> null
        Problem.NOT_LOGGED_IN -> if (providers(a)) t("no provider is signed in on the server", "ningún proveedor tiene la sesión iniciada en el servidor")
            else t("it has no login on the server", "no tiene la sesión iniciada en el servidor")
        Problem.NOT_CONFIGURED -> t("it is not set up on this server", "no está preparado en este servidor")
        else -> reason(a)?.substringBefore(". ")?.trimEnd('.')?.replaceFirstChar { it.lowercase() }
    }
}

/**
 * The agents' pages in [l]: Settings' Agents row, their list and each one's page, from the agents and
 * their state, version and login as the server sends them. Pure, for tests.
 */
class PresentAgents(private val l: Lang) {
    private fun t(en: String, es: String) = l.t(en, es)
    private fun words(freeOnly: Boolean) = AgentWords(l, freeOnly)

    /** Every agent the server knows, in its order; none from an older server. */
    fun agents(state: ChatState): List<AgentEntry> = state.agentList

    /** Settings' Agents row: each agent and its state while they fit on one line; past that, how many are in each state. */
    fun summary(state: ChatState): List<Pair<Tone, String>> {
        val w = words(state.freeOnly)
        val all = agents(state)
        if (all.size <= 2) return all.map { w.state(it).first to w.stateAfterName(it) }
        val ready = all.count { it.ready }
        val counts = all.filterNot { it.ready }.groupBy { w.state(it) }.map { (s, list) -> s.first to "${list.size} ${s.second.lowercase()}" }
        return listOf(Tone.SUCCESS to t("${all.size} agents: $ready ready", "${all.size} agentes: $ready listos")) + counts
    }

    /** Settings, Agents: every agent, in the server's order. */
    fun rows(state: ChatState): List<AgentRowUi> = agents(state).map { a ->
        val w = words(state.freeOnly)
        val why = if (a.ready) w.login(a) else when (a.problem) {
            Problem.NOT_LOGGED_IN -> if (w.providers(a)) t("no provider signed in", "ningún proveedor con sesión") else t("no login", "sin sesión")
            Problem.NOT_CONFIGURED -> t("not set up on this server", "sin preparar en este servidor")
            else -> w.short(a)
        }
        AgentRowUi(a.id, a.info.name, w.state(a), listOfNotNull(a.info.version, why).joinToString(" · ", prefix = " · "))
    }

    /** The note under the agents' list. */
    val rowsNote get() = t("The agents set up on the server, in its order. Tap one for its version, login and provider.",
        "Los agentes preparados en el servidor, en su orden. Toca uno para ver su versión, su sesión y su proveedor.")

    /** One agent's page; [freeOnly]: the owner's switch is on (ChatState.freeOnly). */
    fun info(a: AgentEntry, freeOnly: Boolean = false): AgentInfoUi {
        val w = words(freeOnly)
        return AgentInfoUi(a.info.name, w.state(a), infoRows(a, w), privacy = if (w.providers(a)) privacyLine else null,
            note = infoNote(a, w, freeOnly), problem = infoProblem(a, w))
    }

    private fun infoRows(a: AgentEntry, w: AgentWords): List<Triple<String, String, Tone?>> {
        val signed = a.login?.method != null
        val tone = when {
            signed && a.ready -> Tone.SUCCESS
            !signed -> Tone.WARNING
            else -> null
        }
        return listOfNotNull(
            Triple(t("Version", "Versión"), a.info.version ?: "—", null),
            Triple(if (w.free(a)) t("Free providers", "Proveedores gratuitos") else if (w.providers(a)) t("Providers", "Proveedores")
                else t("Login", "Inicio de sesión"), w.login(a), tone),
            a.remote?.let { Triple(it.name, t("Available", "Disponible"), null) },
        )
    }

    private fun infoNote(a: AgentEntry, w: AgentWords, freeOnly: Boolean): String? = when {
        !a.ready -> null
        w.providers(a) -> (if (freeOnly) t("Only free providers and models whose price is zero.", "Solo proveedores gratuitos y modelos de precio cero.")
            else t("The providers signed in on the server, free or paid. A paid model says so in the model list.",
                "Los proveedores con sesión en el servidor, gratuitos o de pago. Un modelo de pago lo dice en la lista de modelos.")) +
            (if (a.remote == null) t(" No Remote Control: ${a.info.name} does not offer it.", " Sin Remote Control: ${a.info.name} no lo ofrece.") else "")
        freeOnly -> t("Only with your subscription: the server never uses an API key, and a turn that would bill usage credits is stopped.",
            "Solo con tu suscripción: el servidor nunca usa una clave de API, y un turno que cobraría créditos de uso se detiene.")
        else -> t("With the login set up on the server. A model that bills per use says so in the model list.",
            "Con la sesión preparada en el servidor. Un modelo que cobra por uso lo dice en la lista de modelos.")
    }

    private fun infoProblem(a: AgentEntry, w: AgentWords): Pair<String, String>? = w.reason(a)?.let { why ->
        val title = when (a.problem) {
            Problem.NOT_LOGGED_IN -> if (w.providers(a)) t("No provider is signed in", "Ningún proveedor tiene la sesión iniciada")
                else t("${a.info.name} is not signed in", "${a.info.name} no tiene la sesión iniciada")
            Problem.NOT_CONFIGURED -> t("${a.info.name} is not set up", "${a.info.name} no está preparado")
            Problem.CHECKING -> t("Checking ${a.info.name}", "Comprobando ${a.info.name}")
            else -> t("${a.info.name} is not available", "${a.info.name} no está disponible")
        }
        title to why + if (a.problem != Problem.CHECKING) t(" Until then it cannot be chosen for a conversation.",
            " Hasta entonces no se puede elegir para una conversación.") else ""
    }

    /** What free providers do with what they receive, plainly. */
    val privacyLine get() = t("Free providers keep what they receive to improve their models: the Flipper's screen, your messages and files such as NFC or RFID dumps.",
        "Los proveedores gratuitos guardan lo que reciben para mejorar sus modelos: la pantalla del Flipper, tus mensajes y ficheros como volcados de NFC o RFID.")
}

/** Choosing an agent (a new conversation's Agent list, Settings, Agent) and where new conversations start, in [l]. */
class PresentAgentChoice(private val l: Lang) {
    private fun t(en: String, es: String) = l.t(en, es)

    /** Settings, Agent: the default agent and its defaults, with [chat] for the model, effort and mode names. */
    fun prefs(state: ChatState, chat: PresentChat): AgentPrefsUi {
        val status = state.status
        val settings = status?.settings ?: dev.zeffin.link.ChatSettings()
        val entry = state.agentList.firstOrNull { it.id == settings.defaultAgent } ?: state.agentList.firstOrNull()
        val defaults = entry?.let { settings.agents[it.id] } ?: dev.zeffin.link.AgentDefaults(settings.defaultMode, settings.defaultModel,
            settings.defaultEffort)
        val agentStatus = status?.forAgent(entry?.id)
        val levels = agentStatus?.model(defaults.model)?.efforts ?: Effort.entries
        return AgentPrefsUi(
            agent = entry?.info?.name,
            model = chat.modelName(defaults.model, agentStatus),
            effort = if (levels.isEmpty()) null else chat.effortName(defaults.effort.takeIf { it in levels } ?: levels.first()),
            mode = chat.modeName(defaults.mode, agentStatus),
            timeout = chat.timeoutName(settings.permissionTimeoutS),
            note = if (entry == null) t("Each conversation can change them later, from the chat.", "Cada conversación puede cambiarlos después, desde el chat.")
                else t("The last ones chosen in a conversation, or set here. Model, effort and mode are ${entry.info.name}'s; each agent keeps its own. " +
                    "A conversation can change its model, effort and mode later, from the chat.",
                    "Los últimos elegidos en una conversación, o los de aquí. Modelo, esfuerzo y modo son de ${entry.info.name}; cada agente guarda los suyos. " +
                        "Una conversación puede cambiar su modelo, esfuerzo y modo después, desde el chat."),
        )
    }

    /** Settings' Agent row under the Agent card: where new conversations start. */
    fun prefsLine(state: ChatState, chat: PresentChat): String = prefs(state, chat).let { p ->
        t("New conversations: ", "Conversaciones nuevas: ") + listOfNotNull(p.agent, p.model, p.mode).joinToString(" · ")
    }

    /** The agent list of Settings, Agent and of a new conversation: ready ones can be chosen, the others say why not. */
    fun options(state: ChatState, chosen: String?): List<OptionUi<String>> = state.agentList.map { a ->
        val w = AgentWords(l, state.freeOnly)
        val rest = if (a.ready) listOfNotNull(a.info.version, w.login(a).let { if (w.free(a)) t("free providers: $it", "proveedores gratuitos: $it") else it })
            .joinToString(" · ", prefix = " · ") else " · " + (w.reason(a) ?: "")
        OptionUi(a.id, a.info.name, rest, a.id == chosen, enabled = a.ready, lead = w.state(a))
    }

    /** Under the agent list. */
    fun optionsNote(state: ChatState): String {
        val w = AgentWords(l, state.freeOnly)
        val all = state.agentList
        val freeNames = all.filter(w::free).map { it.info.name }
        val providerNames = all.filter(w::providers).map { it.info.name }
        return listOfNotNull(
            t("The agents set up on the server, in its order.", "Los agentes preparados en el servidor, en su orden."),
            if (all.any { !it.ready }) t("One that is not ready is shown with the reason, but it cannot be chosen.",
                "Uno que no está listo aparece con el motivo, pero no se puede elegir.") else null,
            if (!all.all { it.ready }) null
            else if (freeNames.isNotEmpty()) t("${freeNames.joinToString(", ")} uses only free providers, which keep what they receive to improve their models.",
                "${freeNames.joinToString(", ")} usa solo proveedores gratuitos, que guardan lo que reciben para mejorar sus modelos.")
            else if (providerNames.isNotEmpty()) t("With ${providerNames.joinToString(", ")}, free providers keep what they receive to improve their models.",
                "Con ${providerNames.joinToString(", ")}, los proveedores gratuitos guardan lo que reciben para mejorar sus modelos.") else null,
            if (all.all { it.ready }) t("The model, effort and mode come from the last conversation with that agent.",
                "El modelo, el esfuerzo y el modo vienen de la última conversación con ese agente.") else null,
        ).joinToString(" ")
    }
}
