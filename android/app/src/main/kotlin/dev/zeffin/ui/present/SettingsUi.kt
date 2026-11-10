// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

/** A state word in color followed by gray detail, as in the setting rows of the design. */
data class StatusLine(val tone: Tone, val state: String, val rest: String?)

data class SettingsUi(val relay: StatusLine, val server: StatusLine, val version: String, val nav: NavUi, val language: String,
                      val agent: StatusLine? = null,
                      /** Connections' Agents row: each agent's state (PresentAgents.summary); null from a server that lists none. */
                      val agents: List<Pair<Tone, String>>? = null,
                      /** The Agent card's row when the server lists its agents: where new conversations start. */
                      val agentPrefs: String? = null)
