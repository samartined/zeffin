// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zeffin.link.ChatState
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.ChatUi
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.PresentCharacter
import kotlinx.coroutines.delay

/** [ui] with the character: the bar's face and the chat's tail, which bounces for 3 s after a turn ends well. */
@Composable
internal fun withCharacter(ui: ChatUi, state: ChatState, streaming: Map<String, String>, device: Face): ChatUi {
    val lang = LocalLang.current
    val words = remember(lang) { PresentCharacter(lang) }
    return words.chat(ui, state, streaming, device, rememberJustEnded(state.busyHere))
}

/** True for the 3 s after the agent's turn in the open conversation ends. */
@Composable
private fun rememberJustEnded(busy: Boolean): Boolean {
    var was by remember { mutableStateOf(busy) }
    var ended by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        val now = was && !busy
        was = busy
        ended = now
        if (now) {
            delay(PresentCharacter.FINISHED_MS)
            ended = false
        }
    }
    return ended
}
