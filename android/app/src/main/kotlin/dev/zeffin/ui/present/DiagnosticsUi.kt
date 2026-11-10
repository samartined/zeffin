// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

data class LogLineUi(val time: String, val level: String, val tone: Tone, val text: String)

data class DiagnosticsUi(
    val updated: String,
    val state: List<Triple<String, String, Tone?>>,
    val server: List<Triple<String, String, Tone?>>?,
    val recent: List<LogLineUi>,
)

data class LogUi(val tab: Int, val lines: List<LogLineUi>)
