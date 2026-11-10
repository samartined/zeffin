// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.ble

import dev.zeffin.relay.FlipperException
import dev.zeffin.relay.FlipperFailure
import dev.zeffin.relay.FlipperLink
import dev.zeffin.relay.GattOp
import dev.zeffin.relay.FlipperProblem
import dev.zeffin.relay.FlipperStep
import dev.zeffin.relay.FakeSocket
import dev.zeffin.relay.FlipperKey
import dev.zeffin.relay.RelayRemote
import dev.zeffin.relay.RelaySession
import dev.zeffin.relay.rpc.RpcIds
import dev.zeffin.relay.rpc.Wire
import dev.zeffin.relay.RelayLog
import dev.zeffin.relay.RadioState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private fun credit(n: Int) = ByteBuffer.allocate(4).putInt(n).array()

/** A Flipper behind a fake GATT: records writes, answers with what each test sets. */
class FakePort : GattPort {
    override val disconnected = CompletableDeferred<Int>()
    var mtuResult: suspend () -> Int = { 414 }
    var serial = true
    var initialCredit = 1024
    var enableError: Int? = null
    val writes = mutableListOf<ByteArray>()
    /** The characteristic of each write, in order. */
    val writeTargets = mutableListOf<UUID>()
    val enabled = mutableListOf<Pair<UUID, Boolean>>()
    var mtuRequested: Int? = null
    var fastRequested = false
    var acceptFast = true
    var closed = false
    private var handler: (UUID, ByteArray) -> Unit = { _, _ -> }
    private var mtuHandler: (Int) -> Unit = {}

    override suspend fun requestMtu(mtu: Int): Int { mtuRequested = mtu; return mtuResult() }
    override fun onMtu(handler: (Int) -> Unit) { mtuHandler = handler }
    override fun requestFastConnection(): Boolean { fastRequested = true; return acceptFast }
    fun notifyMtu(mtu: Int) = mtuHandler(mtu)
    override suspend fun discoverSerial() = serial
    override suspend fun enable(characteristic: UUID, indication: Boolean) {
        enableError?.let { throw GattError(it, GattOp.SUBSCRIBE) }
        enabled += characteristic to indication
    }
    override suspend fun read(characteristic: UUID) = credit(initialCredit)
    override suspend fun write(characteristic: UUID, value: ByteArray) {
        if (disconnected.isCompleted) throw GattError(disconnected.getCompleted(), GattOp.WRITE)
        writes += value
        writeTargets += characteristic
    }
    override fun onChanged(handler: (UUID, ByteArray) -> Unit) { this.handler = handler }
    override suspend fun close() { closed = true; disconnected.complete(0) }

    fun notify(uuid: UUID, value: ByteArray) = handler(uuid, value)
    fun drop(status: Int) = disconnected.complete(status)
}

class FakeRadio : BleRadio {
    var state = RadioState.ON
    var bond = Bond.BONDED
    /** How many more checks report the link as up; -1 = always. */
    var linkUpChecks = 0
    var connectError: Int? = null
    var port = FakePort()
    val autoConnects = mutableListOf<Boolean>()

    override fun state() = state
    override fun bond(address: String) = bond
    override fun linkConnected(address: String) = when {
        linkUpChecks < 0 -> true
        linkUpChecks > 0 -> { linkUpChecks--; true }
        else -> false
    }
    override suspend fun connect(address: String, autoConnect: Boolean, timeoutMs: Long?): GattPort {
        autoConnects += autoConnect
        connectError?.let { throw GattError(it, GattOp.CONNECT) }
        return port
    }
}

class BleFlipperConnectorTest {
    private val log = RelayLog(null)
    private fun TestScope.connector(radio: FakeRadio, skip: AtomicBoolean = AtomicBoolean(false), scope: CoroutineScope = backgroundScope) =
        BleFlipperConnector(radio, "80:E1:26:00:00:01", "Demo\u001b", log, skip, scope)
    private fun logged() = log.flow.value.map { it.text }

    private suspend fun TestScope.failure(block: suspend () -> Unit): FlipperException {
        val r = async { runCatching { block() } }
        advanceTimeBy(120_000)
        return r.await().exceptionOrNull() as FlipperException
    }

    @Test fun connectsSecuresThenNegotiates() = runTest {
        val radio = FakeRadio()
        val steps = mutableListOf<FlipperStep>()
        val r = async { connector(radio).connect(false) { steps += it } }
        advanceTimeBy(1_000)
        val link = r.await()
        assertEquals(listOf(FlipperStep.CONNECTING, FlipperStep.SECURING, FlipperStep.MTU, FlipperStep.SERVICES, FlipperStep.SUBSCRIBING), steps)
        assertEquals(512, radio.port.mtuRequested)
        assertEquals(414, link.mtu)
        assertTrue(radio.port.fastRequested)  // a short connection interval, so each reply costs little
        assertTrue(logged().any { it.contains("short connection interval requested") })
        assertEquals("Demo", link.name) // cleaned: the name is untrusted
        assertEquals(listOf(FlipperGatt.RX, FlipperGatt.FLOW), radio.port.enabled.map { it.first })
        assertTrue(radio.port.enabled.first().second) // RX by indication
        assertTrue(link.rpcOpen.value)
    }

    @Test fun writesFollowCreditsAndMtu() = runTest {
        val radio = FakeRadio()
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(1_000)
        val link = r.await()
        val w = async { link.write(ByteArray(1500)) }
        runCurrent()
        assertEquals(listOf(411, 411, 202), radio.port.writes.map { it.size })
        radio.port.notify(FlipperGatt.FLOW, credit(1024))
        w.await()
        assertEquals(listOf(411, 411, 202, 411, 65), radio.port.writes.map { it.size })
        // indications reach the session in order
        radio.port.notify(FlipperGatt.RX, byteArrayOf(1))
        radio.port.notify(FlipperGatt.RX, byteArrayOf(2))
        assertEquals(1, link.received.receive()[0].toInt())
        assertEquals(2, link.received.receive()[0].toInt())
    }

    @Test fun theRemotesKeysGoToTheSerialTxLikeTheServersFramesAndNothingElseIsWritten() = runTest {
        val radio = FakeRadio()
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(1_000)
        val link = r.await()
        val socket = FakeSocket()
        val remote = RelayRemote()
        val session = async { RelaySession(socket, link, "0.9.0").remote(remote, null).run() }
        runCurrent()
        val settle = RpcIds.SETTLE_MIN + 1
        socket.serverSends(Wire.request(settle)); runCurrent()
        radio.port.notify(FlipperGatt.RX, Wire.reply(settle)); runCurrent()
        assertTrue(remote.press(FlipperKey.OK, long = false)); runCurrent()
        assertEquals(2, radio.port.writes.size)  // the server's stop and the key, each fits one write
        assertEquals(listOf(FlipperGatt.TX, FlipperGatt.TX), radio.port.writeTargets)
        // the serial service's reset characteristic is not even named: the relay has no way to write it
        assertEquals(setOf("SERVICE", "RX", "TX", "FLOW", "CCC"),
            FlipperGatt::class.java.declaredFields.filter { it.type == UUID::class.java }.map { it.name }.toSet())
        session.cancel()
    }

    @Test fun aLockedFlipperAnnouncesZero() = runTest {
        val radio = FakeRadio().apply { port.initialCredit = 0 }
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(1_000)
        val link = r.await()
        assertFalse(link.rpcOpen.value)
        radio.port.notify(FlipperGatt.FLOW, credit(1024))
        assertTrue(link.rpcOpen.value)
        assertEquals(1, link.rpcGeneration)
        radio.port.notify(FlipperGatt.FLOW, credit(0)) // locks again
        assertFalse(link.rpcOpen.value)
    }

    @Test fun mtuWithoutAnswerKeeps23AndSaysSo() = runTest {
        val radio = FakeRadio().apply { port.mtuResult = { awaitCancellation() } }
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(BleFlipperConnector.MTU_WAIT_MS + 1_000)
        assertEquals(23, r.await().mtu)
        assertTrue(logged().any { it.contains("no answer to the MTU request") })
    }

    @Test fun aRefusedMtuKeeps23AndNamesTheStatus() = runTest {
        val radio = FakeRadio().apply { port.mtuResult = { throw GattError(133, GattOp.MTU) } }
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(1_000)
        assertEquals(23, r.await().mtu)
        assertTrue(logged().any { it.contains("MTU request failed (GATT status 133)") })
    }

    @Test fun aGrantedMtuIsLogged() = runTest {
        val radio = FakeRadio()
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(1_000)
        r.await()
        assertTrue(logged().any { it == "Flipper: MTU negotiated 414" })
    }

    @Test fun aLateOrFlipperStartedMtuChangeIsApplied() = runTest {
        val radio = FakeRadio().apply { port.mtuResult = { awaitCancellation() } }
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(BleFlipperConnector.MTU_WAIT_MS + 1_000)
        val link = r.await()
        assertEquals(23, link.mtu)
        radio.port.notifyMtu(414)
        assertEquals(414, link.mtu)
        assertTrue(logged().any { it == "Flipper: MTU changed to 414" })
        val w = async { link.write(ByteArray(500)) }
        runCurrent()
        assertEquals(listOf(411, 89), radio.port.writes.map { it.size })  // the new MTU sizes the chunks
        w.await()
        radio.port.notifyMtu(9999)  // nonsense is ignored
        assertEquals(414, link.mtu)
    }

    @Test fun aDropDuringMtuSkipsItOnlyNextTime() = runTest {
        val skip = AtomicBoolean(false)
        val radio = FakeRadio()
        radio.port.mtuResult = { radio.port.drop(8); throw GattError(8, GattOp.MTU) }
        val e = failure { connector(radio, skip).connect(false) { } }
        assertEquals(FlipperProblem.DROPPED, e.kind)
        assertTrue(skip.get())
        radio.port = FakePort()
        val r = async { connector(radio, skip).connect(false) { } }
        advanceTimeBy(1_000)
        assertEquals(23, r.await().mtu)
        assertEquals(null, radio.port.mtuRequested)
        assertFalse(skip.get())
        // the attempt after that negotiates again
        radio.port = FakePort()
        val again = async { connector(radio, skip).connect(false) { } }
        advanceTimeBy(1_000)
        assertEquals(414, again.await().mtu)
        assertEquals(512, radio.port.mtuRequested)
    }

    @Test fun bondLostOnlyWithEvidence() = runTest {
        val auth = FakeRadio().apply { port.enableError = 5 }
        assertEquals(FlipperProblem.BOND_LOST, failure { connector(auth).connect(false) { } }.kind)
        val gone = FakeRadio().apply { bond = Bond.NONE }
        assertEquals(FlipperProblem.NOT_BONDED, failure { connector(gone).connect(false) { } }.kind)
        // a drop with no authentication status is just a drop: the engine retries it
        val dropped = FakeRadio()
        dropped.port.enableError = 133
        dropped.port.drop(19)
        assertEquals(FlipperProblem.DROPPED, failure { connector(dropped).connect(false) { } }.kind)
    }

    @Test fun refusesAndExplains() = runTest {
        assertEquals(FlipperProblem.BLUETOOTH_OFF, failure { connector(FakeRadio().apply { state = RadioState.OFF }).connect(false) { } }.kind)
        assertEquals(FlipperProblem.NO_PERMISSION, failure { connector(FakeRadio().apply { state = RadioState.NO_PERMISSION }).connect(false) { } }.kind)
        assertEquals(FlipperProblem.UNREACHABLE, failure { connector(FakeRadio().apply { connectError = TIMEOUT }).connect(false) { } }.kind)
        assertEquals(FlipperProblem.NOT_A_FLIPPER, failure { connector(FakeRadio().apply { port.serial = false }).connect(false) { } }.kind)
    }

    @Test fun waitsBrieflyForTheLastLinkToDropThenSharesOne() = runTest {
        // our previous link drops after a second: connect on a free link
        val dropping = FakeRadio().apply { linkUpChecks = 5 }
        val first = async { connector(dropping).connect(false) { } }
        advanceTimeBy(900)
        assertTrue(dropping.autoConnects.isEmpty())
        advanceTimeBy(2_000)
        first.await()
        assertEquals(listOf(false), dropping.autoConnects)
        // Android keeps the link after pairing: after 3 s it is used, not reported as another app
        val kept = FakeRadio().apply { linkUpChecks = -1 }
        val second = async { connector(kept).connect(false) { } }
        advanceTimeBy(2_900)
        assertTrue(kept.autoConnects.isEmpty())
        advanceTimeBy(2_000)
        second.await()
        assertEquals(listOf(false), kept.autoConnects)
    }

    @Test fun passiveUsesAutoConnect() = runTest {
        val radio = FakeRadio()
        val r = async { connector(radio).connect(true) { } }
        advanceTimeBy(1_000)
        r.await()
        assertEquals(listOf(true), radio.autoConnects)
    }

    @Test fun aDisconnectEndsTheLink() = runTest {
        val radio = FakeRadio()
        val r = async { connector(radio).connect(false) { } }
        advanceTimeBy(1_000)
        val link = r.await()
        radio.port.drop(8)
        runCurrent()
        val e = link.ended.await()
        assertEquals(FlipperProblem.DROPPED, e.kind)
        assertEquals(FlipperFailure.Disconnected(8), e.failure)
        assertTrue(e.message!!.contains("out of range"))
    }

    /** The link's watchers end with it: a scope of its own shows what backgroundScope would hide. */
    private suspend fun TestScope.watchersEnd(end: suspend (FakeRadio, FlipperLink) -> Unit) {
        val radio = FakeRadio()
        val owner = Job()
        val r = async { connector(radio, scope = CoroutineScope(owner)).connect(false) { } }
        advanceTimeBy(1_000)
        val link = r.await()
        assertTrue(owner.children.any { it.isActive })
        end(radio, link)
        link.ended.await()
        val left = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { owner.children.forEach { it.join() } }
        }
        assertTrue("the link's watchers outlived it", left != null)
        assertTrue(link.received.isClosedForReceive)
        owner.cancel()
    }

    @Test fun theWatchersEndWhenTheLinkIsClosed() = runTest { watchersEnd { _, link -> link.close() } }

    @Test fun theWatchersEndWhenTheLinkDrops() = runTest { watchersEnd { radio, _ -> radio.port.drop(8) } }
}
