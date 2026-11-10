// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import dev.zeffin.core.Addresses
import dev.zeffin.core.Pairing
import dev.zeffin.core.PairingQr
import dev.zeffin.core.ServerHttp
import dev.zeffin.core.isPlausibleToken
import dev.zeffin.relay.NetError
import dev.zeffin.relay.describeNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Pairing with a QR code or a pasted pairing text (docs/protocol/pairing/README.md): every request goes over TLS checked
 * against the QR code's key, so a server with another key never sees the code. [start] sends the code and gets the
 * number the server shows too; [wait] waits until the owner confirms on the server, and only then gets the tokens.
 */
class PairExchange(private val http: ServerHttp) {

    sealed class Failure : Exception() {
        /** 401: not a code of the pairing the server is showing now. */
        data object BadCode : Failure()
        /** 410 on the code: it rotated out, or no pairing is open. */
        data object CodeExpired : Failure()
        /** 409: another phone already used this code. */
        data object CodeUsed : Failure()
        /** The server at that address does not have the QR code's key: nothing was sent. */
        data object KeyMismatch : Failure()
        /** 403: the owner refused on the server. */
        data object Refused : Failure()
        /** 410 while waiting: 120 s passed, or the pairing was cancelled or replaced on the server. */
        data object Expired : Failure()
        /** 404 while waiting: the tokens left in an answer that was lost. The owner starts again. */
        data object NotFinished : Failure()
        data class Net(val error: NetError) : Failure()
    }

    /** A request that waits for the owner: [number] is the one both screens show. The ticket never leaves this class. */
    class Waiting internal constructor(internal val ticket: String, val number: String, val expiresInS: Int) {
        override fun toString() = "Waiting(ticket hidden, ${expiresInS}s)"
    }

    /** Sends the QR code's code; the server answers with this request's number. */
    suspend fun start(qr: PairingQr): Result<Waiting> = post(qr, "/app/pair", "code", qr.code) { code, o ->
        when (code) {
            202 -> {
                val ticket = o?.str("pairing")?.takeIf { TICKET.matches(it) }
                val number = o?.str("number")?.takeIf { NUMBER.matches(it) }
                val left = o?.int("expires_in")
                if (ticket != null && number != null && left != null) Result.success(Waiting(ticket, number, left))
                else Result.failure(Failure.Net(NetError.BadResponse))
            }
            401 -> Result.failure(Failure.BadCode)
            409 -> Result.failure(Failure.CodeUsed)
            410 -> Result.failure(Failure.CodeExpired)
            else -> Result.failure(Failure.Net(NetError.Http(code)))
        }
    }

    /**
     * Waits for the owner's answer on the server, a long poll at a time; [onWaiting] gets the seconds left after each.
     * Cancelling the coroutine stops it; [cancel] tells the server.
     */
    suspend fun wait(qr: PairingQr, w: Waiting, onWaiting: (Int) -> Unit = {}): Result<Pairing> {
        while (true) {
            val r: Result<Pairing?> = post(qr, "/app/pair/wait", "pairing", w.ticket) { code, o ->
                when (code) {
                    200 -> paired(qr, o)
                    202 -> {
                        o?.int("expires_in")?.let(onWaiting)
                        Result.success(null)
                    }
                    403 -> Result.failure(Failure.Refused)
                    410 -> Result.failure(Failure.Expired)
                    404 -> Result.failure(Failure.NotFinished)
                    else -> Result.failure(Failure.Net(NetError.Http(code)))
                }
            }
            r.exceptionOrNull()?.let { return Result.failure(it) }
            r.getOrNull()?.let { return Result.success(it) }
        }
    }

    /** Tells the server the phone gave up on this request (best effort: it ends by itself after 120 s anyway). */
    suspend fun cancel(qr: PairingQr, w: Waiting) {
        post(qr, "/app/pair/cancel", "pairing", w.ticket) { _, _ -> Result.success(Unit) }
    }

    /** The confirmed answer: the tokens for the QR code's own server, or a bad answer. */
    private fun paired(qr: PairingQr, o: JsonObject?): Result<Pairing> {
        val phone = o?.str("phone").orEmpty()
        val app = o?.str("app").orEmpty()
        val same = o?.str("url")?.toHttpUrlOrNull()?.let { Addresses.sameServer(it, qr.base) } == true
        return if (same && isPlausibleToken(phone) && isPlausibleToken(app)) Result.success(Pairing(qr.base, qr.key, phone, app))
        else Result.failure(Failure.Net(NetError.BadResponse))
    }

    private suspend fun <T> post(qr: PairingQr, path: String, field: String, value: String,
                                 answer: (Int, JsonObject?) -> Result<T>): Result<T> = withContext(Dispatchers.IO) {
        val url = qr.base.newBuilder().encodedPath(path).build()
        val body = buildJsonObject { put(field, value) }.toString().toRequestBody("application/json".toMediaType())
        try {
            http.newPairCall(Request.Builder().url(url).post(body).build(), qr.key).execute().use { r ->
                answer(r.code, runCatching { Json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull())
            }
        } catch (e: IOException) {
            val error = describeNetworkError(e)
            Result.failure(if (error == NetError.KeyChanged) Failure.KeyMismatch else Failure.Net(error))
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private companion object {
        val TICKET = Regex("[A-Za-z0-9_-]{16,64}")
        val NUMBER = Regex("[0-9]{6}")
    }
}
