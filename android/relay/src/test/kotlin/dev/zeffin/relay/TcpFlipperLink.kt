// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.Socket
import kotlin.concurrent.thread

/** The simulator's Flipper over TCP (python -m bridge.sim --flipper-tcp PORT), in place of Bluetooth. */
class TcpFlipperLink(port: Int) : FlipperLink {
    private val socket = Socket("127.0.0.1", port)
    override val name = "Sim"
    override val mtu = 414
    override val received = Channel<ByteArray>(Channel.UNLIMITED)
    override val rpcOpen = MutableStateFlow(true)
    override val rpcGeneration = 1
    override val ended = CompletableDeferred<FlipperException>()

    init {
        thread(isDaemon = true) {
            val input = socket.getInputStream()
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // arbitrary boundaries, like BLE indications
                    var i = 0
                    while (i < n) {
                        val m = minOf(411, n - i)
                        received.trySend(buf.copyOfRange(i, i + m))
                        i += m
                    }
                }
            } catch (_: Exception) {
            }
            ended.complete(FlipperException(FlipperProblem.DROPPED, FlipperFailure.Disconnected(19)))
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!alive) throw ended.getCompleted()
        socket.getOutputStream().write(data)
        socket.getOutputStream().flush()
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { socket.close() }
        ended.complete(FlipperException(FlipperProblem.CLOSED, FlipperFailure.ClosedByRelay))
    }
}
