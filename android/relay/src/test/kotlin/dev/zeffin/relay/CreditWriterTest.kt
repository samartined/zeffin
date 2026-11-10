// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditWriterTest {
    @Test fun neverWritesMoreThanTheWindowAndWaitsForTheRefill() = runTest {
        val w = CreditWriter()
        w.announce(1024)
        val chunks = mutableListOf<Int>()
        val job = async { w.write(ByteArray(1500), 411) { chunks += it.size } }
        runCurrent()
        assertEquals(listOf(411, 411, 202), chunks) // 1024 bytes, then it waits
        assertEquals(0, w.current)
        w.announce(1024)
        job.await()
        assertEquals(listOf(411, 411, 202, 411, 65), chunks)
        assertEquals(1024 - 476, w.current)
    }

    @Test fun aRefillBeforeTheLastAcknowledgementIsNotLost() = runTest {
        val w = CreditWriter()
        w.announce(1024)
        var acks = 0
        // The Flipper drains and refills while the last chunk's acknowledgement is still on its way.
        w.write(ByteArray(1024), 512) {
            acks++
            if (acks == 2) w.announce(1024)
        }
        assertEquals(1024, w.current) // the full new window: nothing subtracted after the refill
        val chunks = mutableListOf<Int>()
        w.write(ByteArray(1024), 512) { chunks += it.size }
        assertEquals(listOf(512, 512), chunks)
    }

    @Test fun noRefillIsAStall() = runTest {
        val w = CreditWriter(waitMs = 20_000)
        w.announce(10)
        val r = async { runCatching { w.write(ByteArray(20), 411) { } } }
        advanceTimeBy(20_001)
        val e = r.await().exceptionOrNull() as FlipperException
        assertEquals(FlipperProblem.STALLED, e.kind)
    }

    @Test fun zeroMeansTheRpcSessionClosed() = runTest {
        val w = CreditWriter()
        w.announce(1024)
        assertTrue(w.rpcOpen.value)
        assertEquals(1, w.generation)
        w.announce(0)
        assertFalse(w.rpcOpen.value)
        val r = async { runCatching { w.write(ByteArray(5), 411) { } } }
        advanceTimeBy(20_001)
        assertEquals(FlipperProblem.CLOSED, (r.await().exceptionOrNull() as FlipperException).kind)
        w.announce(1024)
        assertEquals(2, w.generation) // unlocked: a new RPC session on the same link
    }
}
