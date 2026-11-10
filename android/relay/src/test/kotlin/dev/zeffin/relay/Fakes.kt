// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope

class FakeLink(
    override val name: String = "Demo",
    override val mtu: Int = 414,
    open: Boolean = true,
) : FlipperLink {
    override val received = Channel<ByteArray>(Channel.UNLIMITED)
    override val rpcOpen = MutableStateFlow(open)
    override var rpcGeneration = if (open) 1 else 0
    override val ended = CompletableDeferred<FlipperException>()
    val written = mutableListOf<ByteArray>()
    /** When set, write() suspends until completed. */
    var gate: CompletableDeferred<Unit>? = null
    var closed = false

    override suspend fun write(data: ByteArray) {
        gate?.await()
        if (!alive) throw ended.getCompleted()
        written += data
    }

    override suspend fun close() {
        closed = true
        ended.complete(FlipperException(FlipperProblem.CLOSED, FlipperFailure.ClosedByRelay))
    }

    fun unlock() {
        rpcGeneration++
        rpcOpen.value = true
    }

    fun drop(failure: FlipperFailure = FlipperFailure.Disconnected(8)) = ended.complete(FlipperException(FlipperProblem.DROPPED, failure))
}

class FakeSocket : PhoneSocket {
    override val events = Channel<SocketEvent>(Channel.UNLIMITED)
    val sent = mutableListOf<Any>() // String or ByteArray, in order
    var closedWith: Pair<Int, String>? = null
    var cancelled = false
    var queued = 0L

    override fun sendText(text: String): Boolean { sent += text; return true }
    override fun sendBinary(bytes: ByteArray): Boolean { sent += bytes; return true }
    override fun queueSize() = queued
    override fun close(code: Int, reason: String) { closedWith = code to reason; events.close() }
    override fun cancel() { cancelled = true; events.close() }

    fun serverSends(bytes: ByteArray) = events.trySend(SocketEvent.Binary(bytes))
    fun texts() = sent.filterIsInstance<String>()
}

class TestClock(private val scope: TestScope) : Clock {
    override fun now() = 1_760_000_000_000L + scope.testScheduler.currentTime
    override fun elapsed() = scope.testScheduler.currentTime
}
