// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySessionTest {
    @Test fun readyFirstThenBufferedFlipperBytesInOrder() = runTest {
        val socket = FakeSocket()
        val link = FakeLink()
        link.received.trySend(byteArrayOf(1, 2))  // arrived before the session existed
        link.received.trySend(byteArrayOf(3))
        val session = async { RelaySession(socket, link, "0.1.0").run() }
        runCurrent()
        val ready = Json.parseToJsonElement(socket.sent[0] as String).jsonObject
        assertEquals("ready", ready["type"]!!.jsonPrimitive.content)
        assertEquals("1", ready["protocol"]!!.jsonPrimitive.content)
        assertEquals("BLE", ready["transport"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", ready["app"]!!.jsonPrimitive.content)
        assertEquals("Demo", ready["device"]!!.jsonPrimitive.content)
        assertEquals("414", ready["mtu"]!!.jsonPrimitive.content)
        assertArrayEquals(byteArrayOf(1, 2), socket.sent[1] as ByteArray)
        assertArrayEquals(byteArrayOf(3), socket.sent[2] as ByteArray)
        session.cancel()
    }

    @Test fun oneWrittenPerFrameOnlyAfterTheWrite() = runTest {
        val socket = FakeSocket()
        val link = FakeLink()
        val gate = CompletableDeferred<Unit>()
        link.gate = gate
        val session = async { RelaySession(socket, link, "0.1.0").run() }
        runCurrent()
        socket.serverSends(ByteArray(100))
        socket.serverSends(ByteArray(200))
        runCurrent()
        assertEquals(1, socket.texts().size) // only ready: the write has not finished
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(PhoneProtocol.WRITTEN, PhoneProtocol.WRITTEN), socket.texts().drop(1))
        assertEquals(listOf(100, 200), link.written.map { it.size })
        session.cancel()
    }

    @Test fun limitsCloseWithTheRightCodes() = runTest {
        suspend fun endWith(setup: (FakeSocket, FakeLink) -> Unit): Pair<SessionEnd, FakeSocket> {
            val socket = FakeSocket()
            val link = FakeLink()
            val s = async { RelaySession(socket, link, "0.1.0").run() }
            runCurrent()
            setup(socket, link)
            runCurrent()
            return s.await() to socket
        }
        val (big, s1) = endWith { s, _ -> s.serverSends(ByteArray(65537)) }
        assertTrue(big is SessionEnd.TooBig)
        assertEquals(1009, s1.closedWith!!.first)
        val (text, s2) = endWith { s, _ -> s.events.trySend(SocketEvent.Text("hola")) }
        assertTrue(text is SessionEnd.ServerText)
        assertEquals(1003, s2.closedWith!!.first)
        val (full, s3) = endWith { s, l ->
            l.gate = CompletableDeferred()
            repeat(66) { s.serverSends(ByteArray(1)) }
        }
        assertTrue(full is SessionEnd.QueueFull)
        assertEquals(1008, s3.closedWith!!.first)
        val (lost, s4) = endWith { _, l -> l.drop(FlipperFailure.Disconnected(8)) }
        assertTrue(lost is SessionEnd.FlipperLost)
        assertEquals(1011, s4.closedWith!!.first)
        assertEquals("Flipper: disconnected: the Flipper stopped answering: out of range or off (status 8)", s4.closedWith!!.second)
        val (closed, s5) = endWith { s, _ -> s.events.trySend(SocketEvent.Closed(1011, "Stopped by the operator")) }
        assertTrue(closed is SessionEnd.ServerClosed)
        assertTrue(s5.cancelled && s5.closedWith == null)
        val (locked, _) = endWith { _, l -> l.rpcOpen.value = false }
        assertTrue(locked is SessionEnd.Locked)
    }

    @Test fun aServerThatDoesNotReadEndsTheSession() = runTest {
        val socket = FakeSocket()
        socket.queued = PhoneProtocol.OUTBOX_LIMIT + 1
        val link = FakeLink()
        val s = async { RelaySession(socket, link, "0.1.0").run() }
        runCurrent()
        link.received.trySend(ByteArray(10))
        runCurrent()
        assertTrue(s.await() is SessionEnd.ServerNotReading)
    }

    @Test fun stoppingClosesNormally() = runTest {
        val socket = FakeSocket()
        val s = async { RelaySession(socket, FakeLink(), "0.1.0").run() }
        runCurrent()
        s.cancel()
        runCurrent()
        assertEquals(1000, socket.closedWith!!.first)
    }

    @Test fun timingLinesSayWhenTheFlipperAnswered() = runTest {
        val socket = FakeSocket()
        val link = FakeLink()
        val clock = TestClock(this)
        val log = RelayLog(null, clock)
        val s = async { RelaySession(socket, link, "0.1.0", timing = log, clock = clock).run() }
        runCurrent()
        socket.serverSends(ByteArray(24))
        runCurrent()
        advanceTimeBy(95); link.received.trySend(ByteArray(6)); runCurrent()
        // screen frames interleaved with the replies are counted, never listed in their place
        repeat(9) { link.received.trySend(ByteArray(411)); runCurrent() }
        advanceTimeBy(315); link.received.trySend(ByteArray(6)); runCurrent()
        advanceTimeBy(310); link.received.trySend(ByteArray(6)); runCurrent()
        advanceTimeBy(3_000)  // quiet: the line is written
        val lines = log.flow.value.map { it.text }
        assertEquals(listOf("RPC: 24 B written to BLE in 0 ms · from the Flipper: +95 ms 6 B, +410 ms 6 B, +720 ms 6 B" +
            " · 9 larger chunks, 3699 B (screen, files)"), lines)
        // the next frame flushes the previous one at once, and no answer says so
        socket.serverSends(ByteArray(10)); runCurrent()
        advanceTimeBy(3_000)
        assertTrue(log.flow.value.last().text.endsWith("nothing within 2.5 s"))
        s.cancel()
    }

    @Test fun withoutTimingNothingIsLogged() = runTest {
        val socket = FakeSocket()
        val link = FakeLink()
        val s = async { RelaySession(socket, link, "0.1.0").run() }
        runCurrent()
        socket.serverSends(ByteArray(24)); runCurrent()
        link.received.trySend(ByteArray(6)); runCurrent()
        assertEquals(3, socket.sent.size)  // ready, written and the Flipper's bytes: the relay itself is unchanged
        assertEquals(listOf(PhoneProtocol.WRITTEN), socket.texts().drop(1))
        s.cancel()
    }
}
