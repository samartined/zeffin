// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.compose.ui.graphics.toArgb
import dev.zeffin.ui.theme.FbColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** FbColors must say what the approved design system says (docs/design/system/tokens.json). */
class ThemeTokensTest {
    @Test fun colorsMatchTheDesignTokens() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/design/system/tokens.json") }.first { it.exists() }
        val tokens = Json.parseToJsonElement(file.readText()).jsonObject["color"]!!.jsonObject["tokens"]!!.jsonArray
        assertEquals(tokens.size, FbColors.byToken.size)
        for (t in tokens) {
            val name = t.jsonObject["name"]!!.jsonPrimitive.content
            val hex = t.jsonObject["value"]!!.jsonPrimitive.content
            val color = FbColors.byToken[name] ?: error("FbColors lacks $name")
            assertEquals(name, "#" + hex.removePrefix("#").lowercase(), "#%06x".format(color.toArgb() and 0xFFFFFF))
        }
    }
}
