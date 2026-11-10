// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.relay.rpc.RpcIds
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

/** The remote inside a relay session: it reads a copy of the Flipper's stream and writes keys between the server's frames. */
class RelayRemoteTest {
    private val settleId = RpcIds.SETTLE_MIN + 77
    private val bitmap = ByteArray(1024) { (it % 251).toByte() }

    private class Rig(val socket: FakeSocket, val link: FakeLink, val remote: RelayRemote, val log: RelayLog, val session: Deferred<SessionEnd>)

    private fun TestScope.rig(): Rig {
        val socket = FakeSocket()
        val link = FakeLink()
        val remote = RelayRemote()
        val clock = TestClock(this)
        val log = RelayLog(null, clock)
        val s = async { RelaySession(socket, link, "0.9.0", clock = clock).remote(remote, log).run() }
        runCurrent()
        return Rig(socket, link, remote, log, s)
    }

    /** The server settles and the Flipper answers: the reader is in step. */
    private fun TestScope.synced(): Rig = rig().apply {
        socket.serverSends(Wire.request(settleId))
        runCurrent()
        link.received.trySend(ByteArray(37) { 0x55 } + Wire.reply(settleId))  // the tail of an old frame, then the reply
        runCurrent()
    }

    /** The relay's key frames among what was written: the ones whose ids are the relay's. */
    private fun keysWritten(link: FakeLink) = link.written.map { RpcReader().feed(it) }.filter { m -> m.all { RpcIds.isRelay(it.id) } }

    @Test fun theServerGetsEveryByteUnchangedAndInOrderWhatTheReaderMakesOfIt() = runTest {
        val r = rig()
        r.socket.serverSends(Wire.request(settleId)); runCurrent()
        val chunks = listOf(byteArrayOf(1, 2, 3), Wire.reply(settleId), Wire.frame(bitmap, 2).copyOfRange(0, 500),
            Wire.frame(bitmap, 2).copyOfRange(500, 1032), byteArrayOf(0, 0, 0, 0), Wire.reply(5))  // a corrupt length too
        for (c in chunks) { r.link.received.trySend(c); runCurrent() }
        val sent = r.socket.sent.filterIsInstance<ByteArray>()
        assertEquals(chunks.size, sent.size)
        chunks.indices.forEach { assertArrayEquals(chunks[it], sent[it]) }
        assertEquals(RemoteKeys.Off(KeysOff.READER_STOPPED), r.remote.keys.value)
        assertNull(r.remote.frames.value)
        assertTrue(r.log.flow.value.any { it.text.contains("could not be read") })
        r.session.cancel()
    }

    @Test fun nothingIsReadUntilTheSettleStopsReplyThenFramesAndKeys() = runTest {
        val r = rig()
        assertEquals(RemoteKeys.Off(KeysOff.SYNCING), r.remote.keys.value)
        r.link.received.trySend(Wire.frame(bitmap)); runCurrent()  // before the server's settle: dropped
        assertNull(r.remote.frames.value)
        r.socket.serverSends(Wire.request(RpcIds.SERVER_MAX, content = 21)); runCurrent()  // restart_stream's stop: not a settle
        assertEquals(RemoteKeys.Off(KeysOff.SYNCING), r.remote.keys.value)
        r.socket.serverSends(Wire.request(settleId)); runCurrent()
        r.link.received.trySend(Wire.frame(bitmap).copyOfRange(300, 1032) + Wire.reply(settleId) + Wire.frame(bitmap, 3)); runCurrent()
        assertArrayEquals(bitmap, r.remote.frames.value!!.data)
        assertEquals(3, r.remote.frames.value!!.orientation)
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        r.session.cancel()
    }

    @Test fun withoutTheSettleReplyInFiveSecondsItReadsFromTheNextChunk() = runTest {
        val r = rig()
        r.socket.serverSends(Wire.request(settleId)); runCurrent()
        r.link.received.trySend(ByteArray(10)); runCurrent()
        advanceTimeBy(5_001)
        r.link.received.trySend(ByteArray(3) { 0x55 }); runCurrent()  // the chunk at the limit is still dropped
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        r.link.received.trySend(Wire.frame(bitmap)); runCurrent()
        assertArrayEquals(bitmap, r.remote.frames.value!!.data)
        assertTrue(r.log.flow.value.any { it.text.contains("5 s") })
        r.session.cancel()
    }

    @Test fun aKeyIsOneFrameOfPressShortReleaseWithRelayIdsAndNoWritten() = runTest {
        val r = synced()
        assertTrue(r.remote.press(FlipperKey.OK, long = false))
        runCurrent()
        val key = keysWritten(r.link).single()
        assertEquals(listOf(RpcIds.RELAY_MIN, RpcIds.RELAY_MIN + 1, RpcIds.RELAY_MIN + 2), key.map { it.id })
        assertTrue(key.all { it.content == 23 && !it.hasNext })
        assertEquals(1, r.socket.texts().count { it == PhoneProtocol.WRITTEN })  // the settle stop's, not the key's
        // built by hand, independently of the encoder: OK is 4; PRESS (0) is left out, SHORT is 2, RELEASE is 1
        fun event(id: Long, type: Int) = Wire.delimited(Wire.uint(1, id),
            Wire.len(23, Wire.uint(1, 4) + (if (type != 0) Wire.uint(2, type.toLong()) else ByteArray(0))))
        val expected = event(RpcIds.RELAY_MIN, 0) + event(RpcIds.RELAY_MIN + 1, 2) + event(RpcIds.RELAY_MIN + 2, 1)
        assertEquals(Wire.hex(expected), Wire.hex(r.link.written.last()))
        r.session.cancel()
    }

    @Test fun theServersFramesGoFirstAndEachGetsItsWritten() = runTest {
        val r = synced()
        val gate = CompletableDeferred<Unit>()
        r.link.gate = gate
        r.socket.serverSends(Wire.request(5, content = 5)); runCurrent()  // the writer is now inside this write
        r.socket.serverSends(Wire.request(6, content = 5))
        r.socket.serverSends(Wire.request(7, content = 5)); runCurrent()
        assertTrue(r.remote.press(FlipperKey.UP, long = true))
        gate.complete(Unit); runCurrent()
        val ids = r.link.written.drop(1).map { RpcReader().feed(it).first().id }
        assertEquals(listOf(5L, 6L, 7L, RpcIds.RELAY_MIN), ids)
        assertEquals(4, r.socket.texts().count { it == PhoneProtocol.WRITTEN })  // settle + three: one per server frame
        r.session.cancel()
    }

    @Test fun noKeyWhileAServerCommandIsOpen() = runTest {
        val r = synced()
        r.socket.serverSends(Wire.request(9, content = 8, hasNext = true)); runCurrent()  // a write in parts
        assertEquals(RemoteKeys.Off(KeysOff.SERVER_COMMAND_OPEN), r.remote.keys.value)
        assertFalse(r.remote.press(FlipperKey.DOWN, long = false))
        r.socket.serverSends(Wire.request(9, content = 8)); runCurrent()  // its last part
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        assertTrue(r.remote.press(FlipperKey.DOWN, long = false)); runCurrent()
        assertEquals(1, keysWritten(r.link).size)
        r.session.cancel()
    }

    @Test fun oneKeyInFlightTheNextGoesOnTheReleasesReply() = runTest {
        val r = synced()
        assertTrue(r.remote.press(FlipperKey.UP, false))
        assertTrue(r.remote.press(FlipperKey.DOWN, false))
        runCurrent()
        assertEquals(1, keysWritten(r.link).size)
        val first = keysWritten(r.link).single().map { it.id }
        r.link.received.trySend(Wire.reply(first[0]) + Wire.reply(first[1])); runCurrent()
        assertEquals(1, keysWritten(r.link).size)  // PRESS and SHORT answered: still waiting for RELEASE
        r.link.received.trySend(Wire.reply(first[2])); runCurrent()
        assertEquals(2, keysWritten(r.link).size)
        r.session.cancel()
    }

    @Test fun noLateBurstTheWaitingKeysAreDroppedAfterASecond() = runTest {
        val r = synced()
        repeat(5) { assertTrue(r.remote.press(FlipperKey.RIGHT, false)) }  // one goes at once, four wait
        assertFalse(r.remote.press(FlipperKey.RIGHT, false))  // four waiting at most
        runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        advanceTimeBy(2); runCurrent()
        assertEquals(RemoteKeys.Off(KeysOff.WAITING_FLIPPER), r.remote.keys.value)
        assertFalse(r.remote.press(FlipperKey.LEFT, false))
        // the agent's long read ends: the replies come, and nothing that waited is sent
        val ids = keysWritten(r.link).single().map { it.id }
        r.link.received.trySend(ids.fold(ByteArray(0)) { a, id -> a + Wire.reply(id) }); runCurrent()
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, keysWritten(r.link).size)
        assertTrue(r.log.flow.value.any { it.text.contains("within 1 s") })
        assertTrue(r.log.flow.value.any { it.text.contains("4 keys not sent") })
        r.session.cancel()
    }

    @Test fun aRefusedKeyIsANoteAndALogLineNeverRetried() = runTest {
        val r = synced()
        r.remote.press(FlipperKey.OK, long = true); runCurrent()
        val ids = keysWritten(r.link).single().map { it.id }
        r.link.received.trySend(Wire.reply(ids[0], status = 17) + Wire.reply(ids[1], status = 17) + Wire.reply(ids[2])); runCurrent()
        val refused = r.remote.refused.value!!
        assertEquals(FlipperKey.OK, refused.key)
        assertTrue(refused.long)
        assertEquals("ERROR_APP_SYSTEM_LOCKED", refused.statusName)
        assertEquals(1, r.log.flow.value.count { it.text.contains("refused") })
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, keysWritten(r.link).size)
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        r.session.cancel()
    }

    @Test fun theRepliesToTheRelaysIdsStillGoToTheServer() = runTest {
        val r = synced()
        r.remote.press(FlipperKey.BACK, false); runCurrent()
        val ids = keysWritten(r.link).single().map { it.id }
        val replies = Wire.reply(ids[0]) + Wire.reply(ids[1]) + Wire.reply(ids[2])
        r.link.received.trySend(replies); runCurrent()
        assertArrayEquals(replies, r.socket.sent.filterIsInstance<ByteArray>().last())
        r.session.cancel()
    }

    @Test fun theSessionsEndTurnsTheKeysOffAndForgetsTheFrame() = runTest {
        val r = synced()
        r.link.received.trySend(Wire.frame(bitmap)); runCurrent()
        assertTrue(r.remote.frames.value != null)
        r.link.drop()
        runCurrent()
        r.session.await()
        assertEquals(RemoteKeys.Off(KeysOff.NO_SESSION), r.remote.keys.value)
        assertNull(r.remote.frames.value)
        assertFalse(r.remote.press(FlipperKey.OK, false))
    }

    @Test fun aNewSessionStartsOverNothingCarriedFromTheLastOne() = runTest {
        val remote = RelayRemote()
        val clock = TestClock(this)
        val link1 = FakeLink()
        val s1 = async { RelaySession(FakeSocket().also { it.serverSends(Wire.request(settleId)); it.serverSends(Wire.request(3, 8, hasNext = true)) },
            link1, "0.9.0", clock = clock).remote(remote, null).run() }
        runCurrent()
        link1.received.trySend(Wire.reply(settleId)); runCurrent()
        assertEquals(RemoteKeys.Off(KeysOff.SERVER_COMMAND_OPEN), remote.keys.value)
        link1.rpcOpen.value = false  // the Flipper locks: the session ends
        runCurrent(); s1.await()
        val link2 = FakeLink()
        val socket2 = FakeSocket()
        val s2 = async { RelaySession(socket2, link2, "0.9.0", clock = clock).remote(remote, null).run() }
        runCurrent()
        assertEquals(RemoteKeys.Off(KeysOff.SYNCING), remote.keys.value)  // in step again only after this session's settle
        socket2.serverSends(Wire.request(settleId + 1)); runCurrent()
        link2.received.trySend(Wire.reply(settleId + 1)); runCurrent()
        assertEquals(RemoteKeys.Ready, remote.keys.value)  // the open command was the old session's
        s2.cancel()
    }

    @Test fun idsCountFromTheRangesStartAndWrapInsideIt() {
        val remote = RelayRemote()
        assertEquals(RpcIds.RELAY_MIN, remote.nextId())
        assertEquals(RpcIds.RELAY_MIN + 1, remote.nextId())
        val end = RelayRemote(firstId = RpcIds.RELAY_MAX)
        assertEquals(RpcIds.RELAY_MAX, end.nextId())
        assertEquals(RpcIds.RELAY_MIN, end.nextId())
    }

    @Test fun aKeyWhoseBytesDoNotReadBackIsNeverWritten() = runTest {
        val r = synced()
        val before = r.link.written.size
        // an encoder bug: the Flipper would answer ERROR_DECODE and end the RPC session it shares with the server
        r.remote.encode = { ids, key, long -> dev.zeffin.relay.rpc.RpcEncoder.keyFrame(ids, key, long).also { it[it.size - 4] = 0x0F } }
        assertTrue(r.remote.press(FlipperKey.OK, false)); runCurrent()
        assertEquals(before, r.link.written.size)
        assertTrue(r.log.flow.value.any { it.text.contains("OK not sent") })
        assertEquals(RemoteKeys.Ready, r.remote.keys.value)
        r.session.cancel()
    }
}
