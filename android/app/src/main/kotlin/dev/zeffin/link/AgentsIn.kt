// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import kotlinx.serialization.json.JsonObject

/** The most agents and models the app keeps from one message. */
private const val MAX_AGENTS = 10
private const val MAX_MODELS = 100

/** An agent's id as the server sends it (defs.json's agentId). */
private val AGENT_ID = Regex("[a-z][a-z0-9-]{0,39}")

/**
 * Reading the agents of /app (hello's and chat.status's "agents", and the settings per agent) into
 * Kotlin types. Anything malformed is dropped, never thrown.
 */
object AgentsIn {
    /** hello's or chat.status's "agents": every agent the server knows, in its order; none when it sends none usable. */
    fun agents(m: JsonObject): List<AgentEntry> = m.objects("agents").mapNotNull(::agentEntry).distinctBy { it.id }.take(MAX_AGENTS)

    /** An agent's id as the server sends it, or null. */
    fun id(value: String?): String? = value?.takeIf { AGENT_ID.matches(it) }

    private fun agentEntry(o: JsonObject): AgentEntry? {
        val id = id(o.s("id")) ?: return null
        val ready = o.b("ready") ?: false
        return AgentEntry(
            id = id,
            info = ChatIn.agent(o) ?: AgentInfo(id, null),
            configured = o.b("configured") ?: false,
            ready = ready,
            problem = if (ready) null else o.s("problem"),
            login = o.obj("login")?.let { Login(it.s("method"), it.b("subscription") ?: false) },
            models = o.objects("models").mapNotNull(::model).distinctBy { it.value }.take(MAX_MODELS),
            modeOptions = modeOptions(o),
            remote = ChatIn.capability(o.obj("remote")),
            attachments = o.b("attachments") ?: false,
        )
    }

    private fun model(o: JsonObject): ModelInfo? {
        val value = o.s("value") ?: return null
        // "available" is required: a model without it cannot be chosen (no fallback for older servers)
        val available = o.b("available") ?: false
        return ModelInfo(value, o.s("label", 40)?.takeIf { it.isNotBlank() } ?: value, o.strings("efforts").mapNotNull(Effort::of),
            o.b("auto") ?: true, available, if (available) null else o.s("reason"), o.b("images") ?: false,
            o.b("paid") ?: false)
    }

    /** chat.status's "settings": the default agent's defaults in the fields today's screens read, and every agent's. */
    fun settings(s: JsonObject?): ChatSettings {
        val defaultAgent = id(s?.s("default_agent"))
        val agents = s?.obj("agents").orEmpty().entries.take(MAX_AGENTS).mapNotNull { (id, value) ->
            val o = (value as? JsonObject)?.takeIf { AGENT_ID.matches(id) } ?: return@mapNotNull null
            id to AgentDefaults(Mode.of(o.s("default_mode")) ?: Mode.MANUAL, o.s("default_model"),
                Effort.of(o.s("default_effort")) ?: Effort.MEDIUM)
        }.toMap()
        val mine = agents[defaultAgent] ?: AgentDefaults()
        return ChatSettings(defaultMode = mine.mode, defaultModel = mine.model, defaultEffort = mine.effort,
            permissionTimeoutS = s?.i("permission_timeout_s") ?: 120, defaultAgent = defaultAgent, agents = agents)
    }
}
