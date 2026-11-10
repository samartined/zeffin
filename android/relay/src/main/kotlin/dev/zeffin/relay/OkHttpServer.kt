// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.NoServerKey
import dev.zeffin.core.NoTunnel
import dev.zeffin.core.ServerKeyChanged
import dev.zeffin.core.ServerHttp
import dev.zeffin.core.cleanText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.coroutines.resume

/** Classifies a network exception, without the address or any header. */
fun describeNetworkError(t: Throwable?): NetError = when (t) {
    null -> NetError.Closed
    is NoTunnel -> NetError.NoTunnel(t.why)
    is ServerKeyChanged -> NetError.KeyChanged
    is NoServerKey -> NetError.NoKey
    is java.net.ConnectException -> NetError.Refused
    is java.net.SocketTimeoutException -> NetError.Timeout
    is java.net.UnknownHostException -> NetError.UnknownHost
    is java.net.NoRouteToHostException -> NetError.NoRoute
    is javax.net.ssl.SSLException -> NetError.Tls
    is java.io.EOFException -> NetError.Eof
    else -> NetError.Other(t.message?.let { cleanText(it, 80) } ?: t.javaClass.simpleName)
}

class OkHttpServerChecker(
    private val checkUrl: HttpUrl,
    private val token: String,
    private val http: ServerHttp,
) : ServerChecker {
    override suspend fun check(): CheckResult = suspendCancellableCoroutine { cont ->
        val call = http.newCall(ServerHttp.Use.RELAY_CHECK, Request.Builder().url(checkUrl).header("Authorization", "Bearer $token").build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(CheckResult.Unreachable(describeNetworkError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    when (it.code) {
                        200 -> parse(it.body.string())
                        401 -> CheckResult.Rejected
                        404 -> CheckResult.Outdated
                        else -> CheckResult.Unreachable(NetError.Http(it.code))
                    }
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    private fun parse(body: String): CheckResult = try {
        val o = Json.parseToJsonElement(body).jsonObject
        val server = o["server"] as? JsonObject
        val relay = o["relay"] as? JsonObject
        CheckResult.Ok(
            ServerInfo(server?.str("name"), server?.str("version")),
            o.str("gate") ?: "active",
            relay?.let {
                AttachedRelay(it.str("app"), it.str("device"),
                    (it["ready"] as? JsonPrimitive)?.booleanOrNull ?: false, (it["age_s"] as? JsonPrimitive)?.doubleOrNull)
            },
        )
    } catch (e: Exception) {
        CheckResult.Unreachable(NetError.BadResponse)
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cleanText(it) }

class OkHttpPhoneDialer(
    private val url: String,
    private val token: String,
    private val http: ServerHttp,
) : PhoneDialer {
    override suspend fun dial(): DialResult = suspendCancellableCoroutine { cont ->
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        var opened = false
        val ws = http.newWebSocket(ServerHttp.Use.RELAY_SOCKET, request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                if (cont.isActive) cont.resume(DialResult.Open(OkHttpPhoneSocket(webSocket, events)))
                else webSocket.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(SocketEvent.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(SocketEvent.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(SocketEvent.Closed(code, cleanText(reason, 100).orEmpty()))
                webSocket.close(1000, null) // answer with a normal close, never echo reserved codes
                events.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened) {
                    val code = response?.code
                    response?.close()
                    if (cont.isActive) {
                        cont.resume(if (code != null) DialResult.Refused(code) else DialResult.Failed(describeNetworkError(t)))
                    }
                } else {
                    events.trySend(SocketEvent.Failure(describeNetworkError(t)))
                    events.close()
                }
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }
}

private class OkHttpPhoneSocket(private val ws: WebSocket, override val events: ReceiveChannel<SocketEvent>) : PhoneSocket {
    override fun sendText(text: String) = ws.send(text)
    override fun sendBinary(bytes: ByteArray) = ws.send(bytes.toByteString())
    override fun queueSize() = ws.queueSize()
    override fun close(code: Int, reason: String) {
        if (!ws.close(code, reason)) ws.cancel()
    }
    override fun cancel() = ws.cancel()
}
