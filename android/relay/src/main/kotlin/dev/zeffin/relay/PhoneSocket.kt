// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.channels.ReceiveChannel

/** What arrives on the /phone WebSocket. */
sealed interface SocketEvent {
    class Binary(val bytes: ByteArray) : SocketEvent
    class Text(val text: String) : SocketEvent
    data class Closed(val code: Int, val reason: String) : SocketEvent
    data class Failure(val error: NetError) : SocketEvent
}

/** An open /phone WebSocket. */
interface PhoneSocket {
    val events: ReceiveChannel<SocketEvent>
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    /** Bytes accepted by sendBinary/sendText but not yet sent. */
    fun queueSize(): Long
    fun close(code: Int, reason: String)
    fun cancel()
}

sealed interface DialResult {
    class Open(val socket: PhoneSocket) : DialResult
    /** The server refused the upgrade: 401 token, 409 another relay, 423 stopped by the operator. */
    data class Refused(val code: Int) : DialResult
    data class Failed(val error: NetError) : DialResult
}

fun interface PhoneDialer {
    suspend fun dial(): DialResult
}

/** What GET /phone/check says. */
sealed interface CheckResult {
    data class Ok(val server: ServerInfo, val gate: String, val relay: AttachedRelay?) : CheckResult
    data object Rejected : CheckResult
    /** 404: a server too old to have /phone/check. */
    data object Outdated : CheckResult
    data class Unreachable(val error: NetError) : CheckResult
}

data class AttachedRelay(val app: String?, val device: String?, val ready: Boolean, val ageS: Double?)

fun interface ServerChecker {
    suspend fun check(): CheckResult
}
