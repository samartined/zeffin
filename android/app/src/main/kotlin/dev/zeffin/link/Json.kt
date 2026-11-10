// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import dev.zeffin.core.cleanText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Field readers for the /app messages. Numbers sent as strings are refused; names are cleaned and shortened. */
internal fun JsonObject.s(k: String, limit: Int = 64) =
    (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cleanText(it, limit) }
internal fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
internal fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal fun JsonObject.obj(k: String) = this[k] as? JsonObject
internal fun JsonObject.objects(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonObject.strings(k: String, limit: Int = 600) =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let { s -> cleanText(s, limit) } }.orEmpty()

/** Text that is shown as it came (Claude's words, Flipper data): kept whole, with its line breaks, but without
 *  control characters or the invisible marks that reorder text. */
internal fun JsonObject.text(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::chatText)

fun chatText(value: String): String = buildString(value.length) {
    for (ch in value) {
        val bidi = ch in '\u202a'..'\u202e' || ch in '\u2066'..'\u2069' || ch == '\u200e' || ch == '\u200f' || ch == '\u061c'
        if ((ch.isISOControl() && ch != '\n' && ch != '\t') || bidi) continue
        append(ch)
    }
}

/**
 * Untrusted tool and permission input as it is shown (commands, paths, keys, plans, tool names), so what the owner
 * reads is what runs: no control characters and no invisible format characters (Cf: the bidi marks, U+061C, the
 * zero-width ones). [multiline] text (a command, a plan) keeps its line breaks and tabs, with U+2028 and U+2029 as
 * plain line breaks; one-line text (a name, a path, keys, a tool) gets spaces for all of them. Both are cut to
 * [limit] characters.
 */
fun inputText(value: String, multiline: Boolean = false, limit: Int = Int.MAX_VALUE): String {
    val out = StringBuilder(value.length)
    value.codePoints().forEach { c ->
        when {
            c == '\n'.code || c == '\t'.code -> out.append(if (multiline) c.toChar() else ' ')
            c == 0x2028 || c == 0x2029 -> out.append(if (multiline) '\n' else ' ')  // line and paragraph separators
            Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() -> {}
            else -> out.appendCodePoint(c)
        }
    }
    return out.toString().take(limit)
}
