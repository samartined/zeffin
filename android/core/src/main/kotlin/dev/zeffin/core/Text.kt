// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

/**
 * What comes from the Flipper (its name, file names, results) is untrusted: keep printable
 * characters only and cut it, like the server's _clean_text.
 */
fun cleanText(value: String?, limit: Int = 64): String? {
    if (value == null) return null
    val text = value.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }.trim()
    return text.take(limit).ifEmpty { null }
}

/** A token shown on screen: only its last four characters. */
fun maskToken(token: String?): String = if (token.isNullOrEmpty()) "" else "•••• " + token.takeLast(4)

/** WebSocket close reasons must fit 123 UTF-8 bytes; the server keeps that much (0.8.0; older ones 64 characters). */
fun closeReason(text: String): String {
    var out = text.take(123)
    while (out.toByteArray().size > 123) out = out.dropLast(1)
    return out
}

/** Tokens from `zeffin` are url-safe base64, 43 characters; accept 32 to 256 of that alphabet. */
fun isPlausibleToken(token: String): Boolean = Regex("[A-Za-z0-9_\\-]{32,256}").matches(token)
