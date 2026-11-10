// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import java.io.File

/**
 * The texts the screenshots cut (a Txt that does not fit: "…" or clipped), one line per screen and text, in
 * build/outputs/roborazzi/<lang>/cut-texts.txt: no label, button or title may show cut in either language.
 */
object TextCuts {
    /**
     * The fixtures' content that a one- or two-line slot may cut by design, with the whole text a tap away: a
     * conversation's title (the app bar, Home's card), a message's preview, the settings line naming a model, the
     * relay's last event. Anything else cut is the app's own words, and a screenshot test fails on it.
     */
    val CONTENT = setOf(
        "Saved infrared remotes", "Install the tool from the link I sent you so you can look for more remotes",
        "What does this file contain?", "Why does the relay drop the session?",
        "This is my TV remote and the file I saved. Which buttons are missing in the file?",
        "OpenCode · Nemotron 3.5 Super VL · Manual",
        "Last event: Traffic: 61 KB from the Flipper, 921 B to the Flipper · 2 s ago",
        "Último evento: Tráfico: 61 KB del Flipper, 921 B al Flipper · hace 2 s",
    )

    @Synchronized
    fun add(lang: Lang, screen: String, text: String) {
        val file = File("build/outputs/roborazzi/${lang.name.lowercase()}/cut-texts.txt")
        file.parentFile.mkdirs()
        val line = "$screen\t${text.replace("\n", "\\n")}"
        if (!file.exists() || line !in file.readLines()) file.appendText(line + "\n")
    }
}
