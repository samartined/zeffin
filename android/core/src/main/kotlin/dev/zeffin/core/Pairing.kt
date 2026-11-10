// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl

/** The server's address, its pinned key and the relay and app tokens it gave, as the app saves them. */
data class Pairing(val base: HttpUrl, val key: ServerAuth.PinnedKey, val phoneToken: String, val appToken: String) {
    override fun toString() = "Pairing(${Addresses.display(base)}, ${key.fingerprint}, tokens hidden)"
}

/**
 * What a pairing QR code holds, and the pairing text that "Paste the pairing text" reads: the same payload
 * (docs/protocol/pairing/README.md). Version 3 only: {"fb":3,"url":"https://<ip>:9434","code":"…","key":"<pin>"}, a
 * code that works once, and the pin of the server's key, checked before the code is sent. Versions 1 and 2 came from
 * servers that spoke cleartext: refused, never a fallback.
 */
data class PairingQr(val base: HttpUrl, val code: String, val key: ServerAuth.PinnedKey) {
    override fun toString() = "PairingQr(${Addresses.display(base)}, ${key.fingerprint}, code hidden)"

    companion object {
        private val CODE = Regex("[A-Za-z0-9_-]{16,64}")

        fun parse(text: String): PairingQr {
            val obj: JsonObject = try {
                Json.parseToJsonElement(text.trim()).jsonObject
            } catch (e: Exception) {
                throw Addresses.Invalid(Addresses.Problem.NOT_A_PAIRING_QR)
            }
            val fb = (obj["fb"] as? JsonPrimitive)?.intOrNull
                ?: throw Addresses.Invalid(Addresses.Problem.NOT_A_PAIRING_QR)
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (fb) {
                3 -> Unit
                1, 2 -> throw Addresses.Invalid(Addresses.Problem.OLD_QR)
                else -> throw Addresses.Invalid(Addresses.Problem.UNKNOWN_QR_VERSION)
            }
            val base = Addresses.parseBase(str("url") ?: throw Addresses.Invalid(Addresses.Problem.QR_WITHOUT_ADDRESS))
            val code = str("code")?.takeIf { CODE.matches(it) } ?: throw Addresses.Invalid(Addresses.Problem.QR_BAD_CODE)
            val key = str("key")?.let(ServerAuth.PinnedKey::parse) ?: throw Addresses.Invalid(Addresses.Problem.QR_BAD_KEY)
            return PairingQr(base, code, key)
        }
    }
}
