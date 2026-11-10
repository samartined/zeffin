// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

/** Settings, Agent: the agent on the server ([name], as hello says it), the starting values of new conversations and the notifications. */
data class AgentUi(
    val name: String,
    val version: String,
    val login: String,
    val state: StatusLine,
    val model: String,
    val effort: String?,
    val mode: String,
    val timeout: String,
    val ready: Boolean,
    /** With the server's agents listed: the page keeps only where new conversations start (PresentAgentChoice.prefs). */
    val prefs: AgentPrefsUi? = null,
)
