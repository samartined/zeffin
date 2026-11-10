// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import okhttp3.HttpUrl
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/**
 * How the server is known to be the owner's (docs/protocol/pairing/README.md). The pairing QR code and the
 * pairing record carry it; only ServerHttp checks a connection against it.
 */
sealed interface ServerAuth {
    /**
     * The server's public key, pinned: the SHA-256 of its SubjectPublicKeyInfo (DER), not of the certificate, so a
     * renewed certificate with the same key still matches. A public value, still kept out of the logs.
     */
    class PinnedKey(sha256: ByteArray) : ServerAuth {
        private val pin = sha256.copyOf()

        init {
            require(pin.size == PIN_BYTES) { "a pin is 32 bytes" }
        }

        /** Whether [spki], a public key's SubjectPublicKeyInfo (DER), is this key. */
        fun matches(spki: ByteArray) = MessageDigest.isEqual(pin, sha256(spki))

        /** As the QR code and the pairing record write it: base64url without padding, 43 characters. */
        val text: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(pin)

        /** What the owner compares with `zf status`: the first 10 bytes in upper-case hex, in 5 groups of 4. */
        val fingerprint: String
            get() = pin.take(FINGERPRINT_BYTES).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

        override fun equals(other: Any?) = other is PinnedKey && MessageDigest.isEqual(pin, other.pin)
        override fun hashCode() = pin.contentHashCode()
        override fun toString() = "PinnedKey($fingerprint)"

        companion object {
            private const val PIN_BYTES = 32
            private const val FINGERPRINT_BYTES = 10
            private val TEXT = Regex("[A-Za-z0-9_-]{43}")

            /** The QR code's `key`, or null when it is not one. */
            fun parse(text: String): PinnedKey? = text.takeIf { TEXT.matches(it) }
                ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
                ?.let { PinnedKey(it) }
                ?.takeIf { it.text == text }  // one spelling only: no stray bits in the last character

            /** The pin of a public key given as its SubjectPublicKeyInfo (DER). */
            fun of(spki: ByteArray) = PinnedKey(sha256(spki))

            private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        }
    }
}

/** The pinned key for a request to [url]: the pairing's own, only for its own server; null for any other. */
fun interface ServerKeys {
    fun keyFor(url: HttpUrl): ServerAuth?
}

/** The server answered with another key than the pinned one: nothing was sent, and the app pairs again. */
class ServerKeyChanged(cause: Throwable? = null) : IOException("the server's key is not the pinned one; nothing was sent", cause)

/** No pinned key for this server (a pairing from before the server's key was pinned, another server, or cleartext): nothing was sent. */
class NoServerKey : IOException("no pinned key for this server; nothing was sent")
