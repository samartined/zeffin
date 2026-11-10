// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.relay.rpc.Pb
import dev.zeffin.relay.rpc.RpcIds
import dev.zeffin.relay.rpc.RpcMessage
import dev.zeffin.relay.rpc.RpcReader
import dev.zeffin.relay.rpc.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote without a server session (docs/architecture.md, "The relay's own use of the link"): the relay holds the
 * link alone, syncs its reader on its own stop, starts the stream with its own id and writes the keys with the same
 * rules as in a session.
 */
class RemoteLinkSessionTest {
    private val bitmap = ByteArray(1024) { (it % 251).toByte() }

    private class Rig(val link: FakeLink, val remote: RelayRemote, val log: RelayLog, val closed: CompletableDeferred<Unit>,
                      val session: RemoteLinkSession, val end: Deferred<RemoteEnd>)

    private fun TestScope.rig(): Rig {
        val link = FakeLink()
        val remote = RelayRemote()
        val clock = TestClock(this)
        val log = RelayLog(null, clock)
        val closed = CompletableDeferred<Unit>()
        val session = RemoteLinkSession(link, remote, log, { RelayStrings.En }, clock)
        val end = async { session.run { closed.await() } }
        runCurrent()
        return Rig(link, remote, log, closed, session, end)
    }

    private fun messages(link: FakeLink): List<RpcMessage> = link.written.flatMap { RpcReader().feed(it) }

    /** The id of the relay's own stop, the first thing it writes. */
    private fun stopId(link: FakeLink) = messages(link).first().id

    private fun TestScope.synced(): Rig = rig().apply {
        link.received.trySend(ByteArray(41) { 0x55 } + Wire.reply(stopId(link)))  // the tail of an old frame, then the reply
        runCurrent()
    }

    @Test fun itStopsAnyOldStreamThenStartsItsOwnWithIdsFromItsRange() = runTest {
        val r = rig()
        val sent = messages(r.link)
        assertEquals(listOf(Pb.GUI_STOP_SCREEN_STREAM_REQUEST, Pb.GUI_START_SCREEN_STREAM_REQUEST), sent.map { it.content })
        assertTrue(sent.all { RpcIds.isRelay(it.id) })
        assertEquals(RemoteKeys.Off(KeysOff.SYNCING), r.remote.keys.value)
        r.end.cancel()
    }

    @Test fun itSyncsOnlyOnTheWholeReplyToItsOwnStop() = runTest {
        val r = rig()
        val stop = stopId(r.link)
        // a frame of the old stream whose bitmap holds the stop's reply bytes with has_next: not the reply
        val lookalike = bitmap.copyOf().also { b -> Wire.reply(stop, hasNext = true).copyInto(b, 100) }
        r.link.received.trySend(Wire.frame(lookalike)); runCurrent()
        assertEquals(RemoteKeys.Off(KeysOff.SYNCING), r.remote.keys.value)
        assertNull(r.remote.frames.value)
        r.link.received.trySend(Wire.reply(stop) + Wire.reply(stop + 1) + Wire.frame(bitmap, 2)); runCurrent()
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        assertArrayEquals(bitmap, r.remote.frames.value!!.data)
        assertEquals(2, r.remote.frames.value!!.orientation)
        r.end.cancel()
    }

    @Test fun theFramesAreNumberedAndStampedForHomesAge() = runTest {
        val r = synced()
        r.link.received.trySend(Wire.frame(bitmap)); runCurrent()
        val first = r.remote.frames.value!!
        advanceTimeBy(700)
        r.link.received.trySend(Wire.frame(bitmap)); runCurrent()
        val second = r.remote.frames.value!!
        assertEquals(first.seq + 1, second.seq)
        assertEquals(700, second.receivedAt - first.receivedAt)
        r.end.cancel()
    }

    @Test fun keysGoAtOnceOneInFlightAndNeverWithWritten() = runTest {
        val r = synced()
        val before = r.link.written.size
        assertTrue(r.remote.press(FlipperKey.OK, long = false))
        assertTrue(r.remote.press(FlipperKey.DOWN, long = true))
        runCurrent()
        val keys = r.link.written.drop(before).map { RpcReader().feed(it) }
        assertEquals(1, keys.size)  // the second waits for the first's RELEASE
        val ok = keys.single()
        assertEquals(listOf(Pb.PRESS, Pb.SHORT, Pb.RELEASE), ok.map { it.input!!.second })
        assertTrue(ok.all { RpcIds.isRelay(it.id) })
        r.link.received.trySend(Wire.reply(ok[0].id) + Wire.reply(ok[1].id) + Wire.reply(ok[2].id)); runCurrent()
        val next = RpcReader().feed(r.link.written.last())
        assertEquals(listOf(Pb.PRESS, Pb.LONG, Pb.RELEASE), next.map { it.input!!.second })
        assertEquals(wire(FlipperKey.DOWN), next[0].input!!.first)
        r.end.cancel()
    }

    @Test fun withoutTheReleasesReplyTheWaitingKeysAreDroppedNotSentLate() = runTest {
        val r = synced()
        r.remote.press(FlipperKey.UP, long = false); runCurrent()
        val first = RpcReader().feed(r.link.written.last())
        r.remote.press(FlipperKey.UP, long = false); runCurrent()
        advanceTimeBy(RemoteSession.KEY_REPLY_MS + 1)
        assertEquals(RemoteKeys.Off(KeysOff.WAITING_FLIPPER), r.remote.keys.value)
        val count = r.link.written.size
        r.link.received.trySend(Wire.reply(first[2].id)); runCurrent()  // late: the keys come back, the dropped one is not sent
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        assertEquals(count, r.link.written.size)
        r.end.cancel()
    }

    @Test fun aParseErrorStopsTheReaderForTheLink() = runTest {
        val r = synced()
        r.link.received.trySend(byteArrayOf(0, 0, 0, 0)); runCurrent()
        assertEquals(RemoteKeys.Off(KeysOff.READER_STOPPED), r.remote.keys.value)
        assertFalse(r.remote.press(FlipperKey.OK, long = false))
        assertTrue(r.log.flow.value.any { it.text.contains("could not be read") })
        r.closed.complete(Unit); runCurrent()
        assertEquals(RemoteEnd.CLOSED, r.end.await())
        assertTrue(r.session.readerStopped)  // the engine does not keep this link
    }

    @Test fun closingStopsItsOwnStreamAndLeavesTheKeysOff() = runTest {
        val r = synced()
        r.closed.complete(Unit); runCurrent()
        assertEquals(RemoteEnd.CLOSED, r.end.await())
        val last = messages(r.link).last()
        assertEquals(Pb.GUI_STOP_SCREEN_STREAM_REQUEST, last.content)
        assertTrue(RpcIds.isRelay(last.id))
        assertEquals(RemoteKeys.Off(KeysOff.NO_SESSION), r.remote.keys.value)
        assertNull(r.remote.frames.value)
        assertFalse(r.session.readerStopped)
        assertFalse(r.link.closed)  // the link goes back to the engine
    }

    @Test fun aLockOrALostLinkEndsIt() = runTest {
        val locked = rig()
        locked.link.rpcOpen.value = false; runCurrent()
        assertEquals(RemoteEnd.LOCKED, locked.end.await())
        val lost = rig()
        lost.link.drop(); runCurrent()
        assertEquals(RemoteEnd.LOST, lost.end.await())
    }

    @Test fun neverIdZeroNorAnIdOutsideTheRelaysRange() = runTest {
        val r = synced()
        repeat(5) {
            r.remote.press(FlipperKey.RIGHT, long = it % 2 == 0); runCurrent()
            val k = RpcReader().feed(r.link.written.last())
            r.link.received.trySend(k.fold(ByteArray(0)) { acc, m -> acc + Wire.reply(m.id) }); runCurrent()
        }
        r.closed.complete(Unit); runCurrent()
        r.end.await()
        assertTrue(messages(r.link).all { RpcIds.isRelay(it.id) })
    }
}
