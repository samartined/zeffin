// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.link.AppProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The fields of one agent's entry that chat.status carried at its top before the agents list. */
private val AGENT_FIELDS = listOf("ready", "problem", "login", "models", "mode_options")
private val DEFAULTS = listOf("default_mode", "default_model", "default_effort")

/**
 * A chat.status the tests write with one agent's fields at its top, as they were written before the agents list
 *: the same message with them in Claude Code's entry, the default agent, and its defaults in the
 * settings' agents. A message without those fields, or with agents already, is left as it is.
 */
internal fun oneAgent(m: JsonObject): JsonObject {
    if ("agents" in m || AGENT_FIELDS.none { it in m }) return m
    val entry = mutableMapOf<String, JsonElement>(
        "id" to JsonPrimitive(AppProtocol.AgentId.CLAUDE_CODE), "name" to JsonPrimitive("Claude Code"), "version" to JsonNull,
        "configured" to JsonPrimitive(true), "remote" to JsonNull, "attachments" to JsonPrimitive(true),
    )
    for (k in AGENT_FIELDS) m[k]?.let { entry[k] = it }
    // every model the tests wrote takes images, unless it says otherwise
    (m["models"] as? JsonArray)?.let { models ->
        entry["models"] = JsonArray(models.map { o ->
            if (o is JsonObject && "images" !in o) JsonObject(o + ("images" to JsonPrimitive(true))) else o
        })
    }
    val out = m.filterKeys { it !in AGENT_FIELDS }.toMutableMap()
    out["agents"] = JsonArray(listOf(JsonObject(entry)))
    (m["settings"] as? JsonObject)?.let { s ->
        val defaults = s.filterKeys { it in DEFAULTS }
        out["settings"] = JsonObject(s.filterKeys { it !in DEFAULTS } + mapOf(
            "default_agent" to JsonPrimitive(AppProtocol.AgentId.CLAUDE_CODE),
            "agents" to JsonObject(mapOf(AppProtocol.AgentId.CLAUDE_CODE to JsonObject(defaults))),
        ))
    }
    return JsonObject(out)
}
